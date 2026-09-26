package com.github.lisama569.versionedconfigreleasestorejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 配置条目（当前态）。
 *
 * <p>并发约束：
 * <ul>
 *   <li>{@code key} 唯一，创建重复键会被数据库拒绝；</li>
 *   <li>{@code optimisticVersion} 为 JPA 乐观锁，并发更新只有一个生效，
 *       其余在提交时以 {@code VERSION_CONFLICT} 失败；</li>
 *   <li>{@code valueVersion} 为业务版本号，从 1 开始单调递增，每次成功修改 +1，
 *       对应 {@link ConfigRevision} 中记录的版本区间。</li>
 * </ul>
 *
 * <p>兼容语义：{@code description}、{@code category} 为后补字段，历史行可能为 null；
 * 服务层统一归一化（null -> "" / "DEFAULT"）。{@code formatVersion} 记录行格式代数，
 * 新写入恒为 {@link #CURRENT_FORMAT_VERSION}。
 */
@Entity
@Table(name = "config_entry", indexes = {
        @Index(name = "uk_entry_key", columnList = "config_key", unique = true),
        @Index(name = "idx_entry_created", columnList = "createdAt,seq")
})
public class ConfigEntry {

    /** 当前行格式代数：v1 行没有 description/category 概念。 */
    public static final int CURRENT_FORMAT_VERSION = 2;
    /** 历史行 category 缺失时的稳定兜底值。 */
    public static final String DEFAULT_CATEGORY = "DEFAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "config_key", nullable = false, length = 200)
    private String key;

    @Column(name = "config_value", nullable = false, length = 4000)
    private String value;

    /** 后补字段：历史数据可能为 null。 */
    @Column(length = 1000)
    private String description;

    /** 后补字段：历史数据可能为 null，DDL 默认值 'DEFAULT'。 */
    @Column(length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryStatus status;

    /** 业务版本号，初始 1。 */
    @Column(nullable = false)
    private long valueVersion;

    /** 行格式代数，历史行由 DDL 默认值补为 1。 */
    @Column(nullable = false)
    private int formatVersion;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long optimisticVersion;

    protected ConfigEntry() {
    }

    public ConfigEntry(String key, String value, String description, String category, Instant now) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.category = category;
        this.status = EntryStatus.DRAFT;
        this.valueVersion = 1L;
        this.formatVersion = CURRENT_FORMAT_VERSION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 应用一次修改，业务版本号 +1。 */
    public void applyChange(String newValue, String newDescription, String newCategory, Instant now) {
        this.value = newValue;
        this.description = newDescription;
        this.category = newCategory;
        this.valueVersion++;
        this.updatedAt = now;
    }

    /**
     * 发布回滚：仅恢复配置值，业务版本号 +1 并追加版本记录；
     * description/category 不属于发布负载，保持现状。
     */
    public void restoreValue(String restoredValue, Instant now) {
        this.value = restoredValue;
        this.valueVersion++;
        this.updatedAt = now;
    }

    public Long getSeq() {
        return seq;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public long getValueVersion() {
        return valueVersion;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getOptimisticVersion() {
        return optimisticVersion;
    }
}
