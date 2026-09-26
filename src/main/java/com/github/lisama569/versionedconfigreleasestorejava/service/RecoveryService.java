package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigVersion;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseOrder;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseStatus;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.EventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEvent;
import com.github.lisama569.versionedconfigreleasestorejava.store.ConfigStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.ReleaseStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.StoreCoordinator;

import java.util.List;

/**
 * 启动恢复：按 seq 顺序重放事件日志，重建 {@link ConfigStore} / {@link ReleaseStore}。
 *
 * <ul>
 *   <li>事件 seq 必须从 1 开始连续无重复；否则判定为 {@code PERSISTENCE_ERROR}，
 *       拒绝在不确定状态下启动。</li>
 *   <li>尾部无换行的半行在 {@code replay()} 阶段已被丢弃（等价于未提交）。</li>
 *   <li>工单直接按事件携带的目标状态 restore（日志是已提交事实），并登记幂等键。</li>
 * </ul>
 * 整个重放在 {@link StoreCoordinator} 写锁内串行执行。
 */
public final class RecoveryService {

    private final StoreCoordinator coordinator;
    private final ConfigStore configStore;
    private final ReleaseStore releaseStore;
    private final EventJournal journal;

    public RecoveryService(StoreCoordinator coordinator,
                           ConfigStore configStore,
                           ReleaseStore releaseStore,
                           EventJournal journal) {
        this.coordinator = coordinator;
        this.configStore = configStore;
        this.releaseStore = releaseStore;
        this.journal = journal;
    }

    /** 重放全部日志，返回已恢复的事件数。 */
    public int recover() {
        List<JournalEvent> events = journal.replay();
        coordinator.writeWithLock(() -> {
            long expectedSeq = 1L;
            for (JournalEvent e : events) {
                if (e.seq() != expectedSeq) {
                    throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                            "事件日志 seq 不连续: 期望=" + expectedSeq + ", 实际=" + e.seq()
                                    + ", type=" + e.type());
                }
                apply(e);
                expectedSeq++;
            }
            coordinator.restoreSeq(expectedSeq - 1);
        });
        return events.size();
    }

    private void apply(JournalEvent e) {
        switch (e.type()) {
            case CONFIG_CREATED -> applyConfigCreated(e);
            case CONFIG_UPDATED -> applyConfigUpdated(e);
            case RELEASE_CREATED -> applyReleaseCreated(e);
            case RELEASE_STATUS_CHANGED -> applyStatusChanged(e);
        }
    }

    private void applyConfigCreated(JournalEvent e) {
        configStore.restoreEntry(new ConfigEntry(e.configKey(), e.version(), e.value(), e.at(), e.at()));
        configStore.restoreVersion(new ConfigVersion(e.configKey(), e.version(), e.value(), e.at(), e.seq()));
    }

    private void applyConfigUpdated(JournalEvent e) {
        ConfigEntry entry = configStore.findEntry(e.configKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                        "更新事件缺少创建事件: key=" + e.configKey() + ", seq=" + e.seq()));
        if (e.version() != entry.getCurrentVersion() + 1) {
            throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                    "版本历史不连续: key=" + e.configKey() + ", 期望="
                            + (entry.getCurrentVersion() + 1) + ", 实际=" + e.version());
        }
        configStore.restoreVersion(
                new ConfigVersion(e.configKey(), e.version(), e.value(), e.at(), e.seq()));
        entry.advanceTo(e.version(), e.value(), e.at());
    }

    private void applyReleaseCreated(JournalEvent e) {
        ReleaseOrder order = ReleaseOrder.restore(
                e.orderId(), e.configKey(), e.version(), ReleaseStatus.DRAFT,
                e.at(), e.at(), e.seq(), null, null, null);
        releaseStore.restoreOrder(order);
        if (e.requestId() != null && e.actionName() != null) {
            releaseStore.restoreIdempotency(e.requestId(),
                    new ReleaseStore.IdempotencyRecord(
                            ReleaseStore.IdempotentAction.valueOf(e.actionName()),
                            e.orderId(), ReleaseStatus.DRAFT, e.at()));
        }
    }

    private void applyStatusChanged(JournalEvent e) {
        ReleaseOrder order = releaseStore.findOrder(e.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                        "状态迁移事件缺少工单创建事件: orderId=" + e.orderId() + ", seq=" + e.seq()));
        ReleaseStatus target = ReleaseStatus.valueOf(e.statusName());
        String submitRid = order.getSubmitRequestId();
        String publishRid = order.getPublishRequestId();
        String rollbackRid = order.getRollbackRequestId();
        if (e.requestId() != null) {
            switch (target) {
                case PENDING -> submitRid = e.requestId();
                case PUBLISHED -> publishRid = e.requestId();
                case ROLLED_BACK -> rollbackRid = e.requestId();
                case DRAFT -> {
                    // 无幂等字段可挂
                }
            }
        }
        ReleaseOrder restored = ReleaseOrder.restore(
                order.getOrderId(), order.getConfigKey(), order.getVersion(), target,
                order.getCreatedAt(), e.at(), order.getSeq(),
                submitRid, publishRid, rollbackRid);
        releaseStore.restoreOrder(restored);
        // 后增字段缺失时旧行按 0 处理；对配置类条目直接重放到记录的生效版本
        configStore.findEntry(e.configKey()).ifPresent(entry ->
                entry.updatePublishedVersion(e.effectivePublishedVersion()));
        if (e.requestId() != null && e.actionName() != null) {
            releaseStore.restoreIdempotency(e.requestId(),
                    new ReleaseStore.IdempotencyRecord(
                            ReleaseStore.IdempotentAction.valueOf(e.actionName()),
                            order.getOrderId(), target, e.at()));
        }
    }
}
