package com.github.lisama569.versionedconfigreleasestorejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 发布单内单个条目的快照。
 *
 * <p>创建发布单时冻结：当时的值与业务版本号（{@code valueVersionAtCreate}）。
 * 发布时记录发布瞬间的版本号（{@code valueVersionAtPublish}）。
 * 回滚前置条件：条目当前业务版本必须等于 {@code valueVersionAtPublish}，
 * 即"发布后没有被人再改过"，否则以 {@code ROLLBACK_PRECONDITION_FAILED} 拒绝，
 * 避免把发布之后的新修改静默覆盖掉。
 */
@Entity
@Table(name = "release_item")
public class ReleaseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(nullable = false)
    private long releaseSeq;

    @Column(nullable = false, length = 200)
    private String entryKey;

    @Column(name = "value_at_create", length = 4000)
    private String valueAtCreate;

    @Column(nullable = false)
    private long valueVersionAtCreate;

    /** 发布成功前为 null，发布成功后写入发布瞬间的版本号。 */
    private Long valueVersionAtPublish;

    protected ReleaseItem() {
    }

    public ReleaseItem(long releaseSeq, String entryKey, String valueAtCreate, long valueVersionAtCreate) {
        this.releaseSeq = releaseSeq;
        this.entryKey = entryKey;
        this.valueAtCreate = valueAtCreate;
        this.valueVersionAtCreate = valueVersionAtCreate;
    }

    public void markPublished(long valueVersionAtPublish) {
        this.valueVersionAtPublish = valueVersionAtPublish;
    }

    public Long getSeq() {
        return seq;
    }

    public long getReleaseSeq() {
        return releaseSeq;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public String getValueAtCreate() {
        return valueAtCreate;
    }

    public long getValueVersionAtCreate() {
        return valueVersionAtCreate;
    }

    public Long getValueVersionAtPublish() {
        return valueVersionAtPublish;
    }
}
