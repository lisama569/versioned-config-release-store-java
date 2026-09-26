package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseOrder;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseStatus;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.EventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEvent;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEventType;
import com.github.lisama569.versionedconfigreleasestorejava.store.ConfigStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.ReleaseStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.StoreCoordinator;
import com.github.lisama569.versionedconfigreleasestorejava.support.Clock;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布状态流转服务。
 *
 * <p>每个写请求都必须携带非空 {@code requestId} 作为幂等键。服务端在写临界区内：
 * <ol>
 *   <li>先查幂等登记：命中且动作/目标一致 -> 直接返回<b>首次结果</b>（{@code replayed=true}），
 *       不再次迁移状态；命中但动作或工单不同 -> {@code IDEMPOTENCY_KEY_CONFLICT}，
 *       与成功重放明确区分；</li>
 *   <li>做存在性与状态机校验（{@code RELEASE_NOT_FOUND} / {@code VERSION_NOT_FOUND} /
 *       {@code ILLEGAL_TRANSITION}）；</li>
 *   <li>分配 seq、追加仅追加日志（含迁移后生效版本）、提交内存态与幂等登记。</li>
 * </ol>
 * 因此重复提交既不会重复生效，重放返回也与首次结论一致；异常发生在落盘前则无任何副作用。
 *
 * <p>合法迁移由 {@link ReleaseOrder} 强制：
 * DRAFT→PENDING→PUBLISHED→ROLLED_BACK，PENDING→ROLLED_BACK 表示撤销尚未生效的发布；
 * ROLLED_BACK 为终态，其余组合一律 {@code ILLEGAL_TRANSITION}。
 *
 * <p>回滚后实际生效版本 = 剩余 PUBLISHED 工单中的最高版本（没有则为 0，即回退到未发布）。
 */
public final class ReleaseService {

    /** 建工单结果。{@code replayed=true} 表示这是重复请求的重放返回。 */
    public record DraftResult(String orderId, String configKey, long version,
                              ReleaseStatus status, boolean replayed) {
    }

    /** 状态迁移结果，携带迁移后实际生效的已发布版本。 */
    public record TransitionResult(String orderId, ReleaseStatus status,
                                   long effectivePublishedVersion, boolean replayed) {
    }

    private final StoreCoordinator coordinator;
    private final ConfigStore configStore;
    private final ReleaseStore releaseStore;
    private final EventJournal journal;
    private final Clock clock;

    public ReleaseService(StoreCoordinator coordinator,
                          ConfigStore configStore,
                          ReleaseStore releaseStore,
                          EventJournal journal,
                          Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.releaseStore = Objects.requireNonNull(releaseStore, "releaseStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- 建草稿工单（幂等） --------------------------------------------

    public DraftResult createDraft(String configKey, long version, String requestId) {
        requireRequestId(requestId);
        if (configKey == null || configKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "configKey 不能为空, requestId=" + requestId);
        }
        if (version < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "version 必须 >= 1, 输入=" + version + ", key=" + configKey
                            + ", requestId=" + requestId);
        }
        return coordinator.writeWithLock(() -> {
            var existing = releaseStore.findIdempotency(requestId).orElse(null);
            if (existing != null) {
                if (existing.getAction() != ReleaseStore.IdempotentAction.CREATE_DRAFT) {
                    throw idempotencyConflict(requestId, existing.getAction(),
                            ReleaseStore.IdempotentAction.CREATE_DRAFT);
                }
                ReleaseOrder first = requireOrder(existing.getOrderId());
                if (!first.getConfigKey().equals(configKey) || first.getVersion() != version) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "幂等键复用冲突: requestId=" + requestId + " 首次目标=("
                                    + first.getConfigKey() + ", v" + first.getVersion()
                                    + "), 本次目标=(" + configKey + ", v" + version + ")");
                }
                return new DraftResult(first.getOrderId(), first.getConfigKey(),
                        first.getVersion(), first.getStatus(), true);
            }
            if (configStore.findEntry(configKey).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFIG_NOT_FOUND,
                        "无法建发布单: 配置不存在, key=" + configKey
                                + "；判定依据: entries 中无此键");
            }
            if (configStore.findVersion(configKey, version).isEmpty()) {
                throw new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "无法建发布单: 配置版本不存在, key=" + configKey + ", version=" + version
                                + "；判定依据: 配置存在但 history 中无此版本号");
            }
            Instant now = clock.now();
            long seq = coordinator.nextSeq();
            String orderId = "D-" + UUID.randomUUID();
            journal.append(new JournalEvent(JournalEventType.RELEASE_CREATED, seq, now,
                    configKey, version, null, orderId, null,
                    requestId, ReleaseStore.IdempotentAction.CREATE_DRAFT.name(), 0L));
            ReleaseOrder order = new ReleaseOrder(orderId, configKey, version, now, seq);
            releaseStore.addOrder(order);
            releaseStore.putIdempotency(requestId, new ReleaseStore.IdempotencyRecord(
                    ReleaseStore.IdempotentAction.CREATE_DRAFT, orderId, ReleaseStatus.DRAFT, now));
            return new DraftResult(orderId, configKey, version, ReleaseStatus.DRAFT, false);
        });
    }

    // ---- 提交待发布（DRAFT -> PENDING，幂等） ---------------------------

    public TransitionResult submit(String orderId, String requestId) {
        requireRequestId(requestId);
        requireOrderId(orderId);
        return coordinator.writeWithLock(() -> {
            ReleaseOrder order = requireOrder(orderId);
            var existing = matchIdempotency(requestId, ReleaseStore.IdempotentAction.SUBMIT, orderId);
            if (existing != null) {
                return replayResult(order, existing);
            }
            // markPending 在状态非 DRAFT 时抛 ILLEGAL_TRANSITION
            order.markPending(requestId, clock.now());
            long effective = requireEntry(order.getConfigKey()).getPublishedVersion();
            appendStatusEvent(order, ReleaseStatus.PENDING, requestId,
                    ReleaseStore.IdempotentAction.SUBMIT, effective);
            releaseStore.putIdempotency(requestId, new ReleaseStore.IdempotencyRecord(
                    ReleaseStore.IdempotentAction.SUBMIT, orderId, ReleaseStatus.PENDING, clock.now()));
            return new TransitionResult(orderId, ReleaseStatus.PENDING, effective, false);
        });
    }

    // ---- 正式发布（PENDING -> PUBLISHED，幂等） -------------------------

    public TransitionResult publish(String orderId, String requestId) {
        requireRequestId(requestId);
        requireOrderId(orderId);
        return coordinator.writeWithLock(() -> {
            ReleaseOrder order = requireOrder(orderId);
            var existing = matchIdempotency(requestId, ReleaseStore.IdempotentAction.PUBLISH, orderId);
            if (existing != null) {
                return replayResult(order, existing);
            }
            ConfigEntry entry = requireEntry(order.getConfigKey());
            if (configStore.findVersion(order.getConfigKey(), order.getVersion()).isEmpty()) {
                throw new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "无法发布: 目标版本不存在, orderId=" + orderId
                                + ", key=" + order.getConfigKey() + ", version=" + order.getVersion());
            }
            long activeBefore = releaseStore.activePublishedVersion(order.getConfigKey());
            if (order.getVersion() <= activeBefore) {
                throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                        "非法发布: orderId=" + orderId + ", 目标版本=v" + order.getVersion()
                                + " 不高于当前生效版本 v" + activeBefore
                                + "；只允许发布更新的版本（判定依据: activePublishedVersion）");
            }
            order.markPublished(requestId, clock.now()); // 非 PENDING 时抛 ILLEGAL_TRANSITION
            long effective = releaseStore.activePublishedVersion(order.getConfigKey());
            entry.updatePublishedVersion(effective);
            appendStatusEvent(order, ReleaseStatus.PUBLISHED, requestId,
                    ReleaseStore.IdempotentAction.PUBLISH, effective);
            releaseStore.putIdempotency(requestId, new ReleaseStore.IdempotencyRecord(
                    ReleaseStore.IdempotentAction.PUBLISH, orderId, ReleaseStatus.PUBLISHED, clock.now()));
            return new TransitionResult(orderId, ReleaseStatus.PUBLISHED, effective, false);
        });
    }

    // ---- 回滚（PUBLISHED/PENDING -> ROLLED_BACK，幂等） -----------------

    public TransitionResult rollback(String orderId, String requestId) {
        requireRequestId(requestId);
        requireOrderId(orderId);
        return coordinator.writeWithLock(() -> {
            ReleaseOrder order = requireOrder(orderId);
            var existing = matchIdempotency(requestId,
                    ReleaseStore.IdempotentAction.ROLLBACK, orderId);
            if (existing != null) {
                return replayResult(order, existing);
            }
            ConfigEntry entry = requireEntry(order.getConfigKey());
            // 非 PENDING/PUBLISHED 时抛 ILLEGAL_TRANSITION
            order.markRolledBack(requestId, clock.now());
            long effective = releaseStore.activePublishedVersion(order.getConfigKey());
            entry.updatePublishedVersion(effective);
            appendStatusEvent(order, ReleaseStatus.ROLLED_BACK, requestId,
                    ReleaseStore.IdempotentAction.ROLLBACK, effective);
            releaseStore.putIdempotency(requestId, new ReleaseStore.IdempotencyRecord(
                    ReleaseStore.IdempotentAction.ROLLBACK, orderId, ReleaseStatus.ROLLED_BACK, clock.now()));
            return new TransitionResult(orderId, ReleaseStatus.ROLLED_BACK, effective, false);
        });
    }

    // ---- 内部辅助（均在写临界区内） -------------------------------------

    private void appendStatusEvent(ReleaseOrder order,
                                   ReleaseStatus target,
                                   String requestId,
                                   ReleaseStore.IdempotentAction action,
                                   long effectivePublishedVersion) {
        long seq = coordinator.nextSeq();
        journal.append(new JournalEvent(JournalEventType.RELEASE_STATUS_CHANGED, seq, clock.now(),
                order.getConfigKey(), order.getVersion(), null, order.getOrderId(),
                target.name(), requestId, action.name(), effectivePublishedVersion));
    }

    /**
     * 校验幂等键。返回非 null 表示命中且可重放；动作或目标不一致抛
     * {@code IDEMPOTENCY_KEY_CONFLICT}；未登记返回 null。
     */
    private ReleaseStore.IdempotencyRecord matchIdempotency(
            String requestId, ReleaseStore.IdempotentAction action, String orderId) {
        var existing = releaseStore.findIdempotency(requestId).orElse(null);
        if (existing == null) {
            return null;
        }
        if (existing.getAction() != action) {
            throw idempotencyConflict(requestId, existing.getAction(), action);
        }
        if (!existing.getOrderId().equals(orderId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "幂等键复用冲突: requestId=" + requestId + " 首次作用于工单="
                            + existing.getOrderId() + ", 本次作用于工单=" + orderId);
        }
        return existing;
    }

    private TransitionResult replayResult(ReleaseOrder order,
                                          ReleaseStore.IdempotencyRecord record) {
        long effective = configStore.findEntry(order.getConfigKey())
                .map(ConfigEntry::getPublishedVersion).orElse(0L);
        // 返回首次完成时的状态，保证“重放结果与首次一致”；replayed 标记未产生新副作用
        return new TransitionResult(record.getOrderId(), record.getResultingStatus(),
                effective, true);
    }

    private ReleaseOrder requireOrder(String orderId) {
        return releaseStore.findOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RELEASE_NOT_FOUND,
                        "发布工单不存在: orderId=" + orderId + "；判定依据: orders 中无此 id"));
    }

    private ConfigEntry requireEntry(String configKey) {
        return configStore.findEntry(configKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFIG_NOT_FOUND,
                        "配置不存在: key=" + configKey + "；发布流程要求配置条目存在"));
    }

    private static BusinessException idempotencyConflict(
            String requestId,
            ReleaseStore.IdempotentAction firstAction,
            ReleaseStore.IdempotentAction thisAction) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "幂等键复用冲突: requestId=" + requestId + " 首次动作=" + firstAction
                        + ", 本次动作=" + thisAction + "；拒绝执行以避免语义混淆");
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "requestId 不能为空（发布接口要求显式幂等键）");
        }
    }

    private static void requireOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "orderId 不能为空");
        }
    }
}
