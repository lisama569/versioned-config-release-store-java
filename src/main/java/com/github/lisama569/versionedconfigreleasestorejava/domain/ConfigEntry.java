package com.github.lisama569.versionedconfigreleasestorejava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 配置条目当前态。
 *
 * <p>一个 {@code configKey} 唯一对应一个条目；每次成功修改都会把
 * {@link #currentVersion} 单调递增，并把 {@link #currentValue} 原子切换为新版本内容。
 * 修改的历史形态由 {@link ConfigVersion} 保留。
 *
 * <p>除“最新草稿版本”外，还记录 {@link #publishedVersion}：当前对读流量生效的已发布版本
 * （0 表示从未发布）。发布成功时推进，回滚时回退到“上一个仍生效的已发布版本”（无则 0）。
 *
 * <p>并发约束：版本号采用乐观并发控制，调用方必须带上“我基于哪个版本修改”的
 * 期望版本号；版本号不匹配即判定为 {@code VERSION_CONFLICT}，同一 key 的并发修改
 * 只有一个能生效。
 *
 * <p><b>兼容性：</b>{@code publishedVersion} 是后增字段，旧数据恢复时取默认值 0，
 * 语义为“尚无已发布版本”，确定且稳定（详见 README 兼容规则）。
 */
public final class ConfigEntry {

    private final String configKey;
    private long currentVersion;
    private String currentValue;
    private final Instant createdAt;
    private Instant updatedAt;
    private long publishedVersion;

    public ConfigEntry(String configKey,
                       long currentVersion,
                       String currentValue,
                       Instant createdAt,
                       Instant updatedAt) {
        this(configKey, currentVersion, currentValue, createdAt, updatedAt, 0L);
    }

    public ConfigEntry(String configKey,
                       long currentVersion,
                       String currentValue,
                       Instant createdAt,
                       Instant updatedAt,
                       long publishedVersion) {
        this.configKey = Objects.requireNonNull(configKey, "configKey");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must be >= 0");
        }
        if (publishedVersion < 0 || publishedVersion > currentVersion) {
            throw new IllegalArgumentException(
                    "publishedVersion 必须位于 [0, currentVersion]: " + publishedVersion);
        }
        this.currentVersion = currentVersion;
        this.currentValue = currentValue;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.publishedVersion = publishedVersion;
    }

    /**
     * 在持有存储锁的前提下把条目推进到新版本。版本号必须严格递增，调用方保证。
     *
     * <p><b>仅允许存储层在写临界区内调用</b>，业务代码不得直接使用，否则会绕过
     * 乐观并发校验。
     */
    public void advanceTo(long nextVersion, String newValue, Instant at) {
        if (nextVersion != this.currentVersion + 1) {
            throw new IllegalStateException(
                    "non-monotonic version: current=" + currentVersion + ", next=" + nextVersion);
        }
        this.currentVersion = nextVersion;
        this.currentValue = newValue;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * 在持有存储锁的前提下更新当前生效的已发布版本。仅存储层/发布服务在写临界区内调用。
     * 值必须是一个已存在的版本号或 0（回滚到“从未发布”）。
     */
    public void updatePublishedVersion(long publishedVersion) {
        if (publishedVersion < 0 || publishedVersion > this.currentVersion) {
            throw new IllegalArgumentException(
                    "publishedVersion 非法: " + publishedVersion + ", current=" + currentVersion);
        }
        this.publishedVersion = publishedVersion;
    }

    public String getConfigKey() {
        return configKey;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getPublishedVersion() {
        return publishedVersion;
    }
}
