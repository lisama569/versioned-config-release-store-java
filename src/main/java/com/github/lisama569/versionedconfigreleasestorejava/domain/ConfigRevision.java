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

import java.time.Instant;

/**
 * 配置版本记录（append-only，不可变）。
 *
 * <p>每次创建/修改条目时在同一事务内追加一行，记录变更前后的业务版本号与值。
 * 与 {@link ConfigEntry} 的更新共享事务，因此不可能出现"条目已更新但没有版本记录"的半更新状态。
 */
@Entity
@Table(name = "config_revision", indexes = {
        @Index(name = "idx_revision_entry", columnList = "entrySeq,seq"),
        @Index(name = "idx_revision_created", columnList = "createdAt,seq")
})
public class ConfigRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(nullable = false)
    private long entrySeq;

    @Column(nullable = false, length = 200)
    private String entryKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeType changeType;

    /** 变更前业务版本；CREATE 时为 0。 */
    @Column(nullable = false)
    private long versionFrom;

    /** 变更后业务版本；CREATE 时为 1。 */
    @Column(nullable = false)
    private long versionTo;

    @Column(length = 4000)
    private String oldValue;

    @Column(length = 4000)
    private String newValue;

    @Column(length = 100)
    private String operator;

    @Column(nullable = false)
    private Instant createdAt;

    protected ConfigRevision() {
    }

    public ConfigRevision(long entrySeq, String entryKey, ChangeType changeType,
                          long versionFrom, long versionTo,
                          String oldValue, String newValue, String operator, Instant now) {
        this.entrySeq = entrySeq;
        this.entryKey = entryKey;
        this.changeType = changeType;
        this.versionFrom = versionFrom;
        this.versionTo = versionTo;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.operator = operator;
        this.createdAt = now;
    }

    public Long getSeq() {
        return seq;
    }

    public long getEntrySeq() {
        return entrySeq;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public long getVersionFrom() {
        return versionFrom;
    }

    public long getVersionTo() {
        return versionTo;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getOperator() {
        return operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
