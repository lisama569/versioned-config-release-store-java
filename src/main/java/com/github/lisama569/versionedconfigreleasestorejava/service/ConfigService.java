package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigVersion;
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
import java.util.Optional;

/**
 * 配置条目创建 / 修改服务。
 *
 * <p><b>并发模型（乐观并发控制 + 单一写临界区）：</b>
 * <ul>
 *   <li>创建：key 已存在则 {@code VERSION_CONFLICT}（区分“键冲突”与“版本过期”）；</li>
 *   <li>修改：调用方携带 {@code expectedVersion}（它所基于的版本）。当前版本与之不同说明
 *       已有人先行提交，本请求以 {@code VERSION_CONFLICT} 拒绝，message 给出期望/实际版本，
 *       因此同一 key 并发修改只有一个结果生效，其余失败原因明确可区分。</li>
 * </ul>
 *
 * <p><b>原子性：</b>所有校验在写锁内先完成；通过后“分配 seq -> 追加日志 -> 提交内存”，
 * 任一步抛异常都不会执行到内存提交（日志失败也会向上传播，调用方视为本次失败可安全重试）。
 * 内存态提交本身是一次对象引用/字段的整体推进，不存在半更新。
 */
public final class ConfigService {

    /** 创建结果：当前条目 + 产生的版本号。 */
    public record CreateResult(String configKey, long version, String value) {
    }

    /** 修改结果：新当前版本号。 */
    public record UpdateResult(String configKey, long previousVersion, long version, String value) {
    }

    private final StoreCoordinator coordinator;
    private final ConfigStore configStore;
    private final ReleaseStore releaseStore;
    private final EventJournal journal;
    private final Clock clock;

    public ConfigService(StoreCoordinator coordinator,
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

    /**
     * 创建配置条目（同时产生 v1）。
     *
     * @throws BusinessException CONFIG 键为空 -> VALIDATION_ERROR；键已存在 -> VERSION_CONFLICT
     */
    public CreateResult create(String configKey, String value) {
        validateKey(configKey);
        String safeValue = value == null ? "" : value;
        return coordinator.writeWithLock(() -> {
            if (configStore.findEntry(configKey).isPresent()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                        "创建冲突: configKey=" + configKey + " 已存在，当前版本="
                                + configStore.findEntry(configKey).get().getCurrentVersion()
                                + "；创建只能作用于不存在的 key");
            }
            Instant now = clock.now();
            long seq = coordinator.nextSeq();
            journal.append(new JournalEvent(JournalEventType.CONFIG_CREATED, seq, now,
                    configKey, 1L, safeValue, null, null, null, null));
            ConfigEntry created = configStore.createEntry(configKey, safeValue, now, seq);
            return new CreateResult(configKey, created.getCurrentVersion(), safeValue);
        });
    }

    /**
     * 基于期望版本修改配置。
     *
     * @param expectedVersion 调用方读取到的当前版本；与存储当前版本不一致即冲突
     * @throws BusinessException key 不存在 -> CONFIG_NOT_FOUND；版本过期 -> VERSION_CONFLICT
     */
    public UpdateResult update(String configKey, long expectedVersion, String value) {
        validateKey(configKey);
        if (expectedVersion < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "expectedVersion 必须 >= 1, 输入=" + expectedVersion + ", key=" + configKey);
        }
        String safeValue = value == null ? "" : value;
        return coordinator.writeWithLock(() -> {
            ConfigEntry entry = configStore.findEntry(configKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFIG_NOT_FOUND,
                            "配置不存在: configKey=" + configKey
                                    + "；修改前必须先创建，判定依据: entries 中无此键"));
            long actual = entry.getCurrentVersion();
            if (actual != expectedVersion) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                        "并发修改冲突: configKey=" + configKey + ", 请求基于版本=" + expectedVersion
                                + ", 存储当前版本=" + actual
                                + "；判定依据: 乐观锁版本号不相等，已有先行提交生效");
            }
            Instant now = clock.now();
            long seq = coordinator.nextSeq();
            long nextVersion = actual + 1;
            journal.append(new JournalEvent(JournalEventType.CONFIG_UPDATED, seq, now,
                    configKey, nextVersion, safeValue, null, null, null, null));
            ConfigVersion v = configStore.appendVersion(entry, safeValue, now, seq);
            return new UpdateResult(configKey, actual, v.getVersion(), safeValue);
        });
    }

    /** 读取当前态（无锁快照）。 */
    public Optional<ConfigEntry> get(String configKey) {
        validateKey(configKey);
        return configStore.findEntry(configKey);
    }

    private static void validateKey(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "configKey 不能为空, 输入=" + configKey);
        }
    }
}
