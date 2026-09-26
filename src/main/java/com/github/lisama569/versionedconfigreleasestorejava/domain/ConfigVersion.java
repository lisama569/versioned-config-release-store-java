package com.github.lisama569.versionedconfigreleasestorejava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 配置条目某次成功修改后形成的不可变版本快照。
 *
 * <p>{@code (configKey, version)} 唯一标识一个版本，版本号在同一 key 内从 1 开始单调递增
 * （创建条目即产生 v1）。{@link #seq} 是全存储范围的单调递增序号，仅用于分页排序的
 * 确定性决胜：创建时间可能相同，但 seq 一定不同且严格按写入顺序递增，因此
 * “按时间 + seq”的键集分页在持续写入下不会重复也不会遗漏。
 */
public final class ConfigVersion {

    private final String configKey;
    private final long version;
    private final String value;
    private final Instant createdAt;
    private final long seq;

    public ConfigVersion(String configKey,
                         long version,
                         String value,
                         Instant createdAt,
                         long seq) {
        this.configKey = Objects.requireNonNull(configKey, "configKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must start from 1");
        }
        this.version = version;
        this.value = value;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0");
        }
        this.seq = seq;
    }

    public String getConfigKey() {
        return configKey;
    }

    public long getVersion() {
        return version;
    }

    public String getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getSeq() {
        return seq;
    }
}
