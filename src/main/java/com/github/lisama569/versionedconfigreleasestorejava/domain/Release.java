package com.github.lisama569.versionedconfigreleasestorejava.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 发布单聚合根。
 *
 * <p>幂等：{@code requestId} 唯一。同一 requestId 重复提交任意写操作：
 * <ul>
 *   <li>请求体一致 → 返回首次结果（状态不变、不重复生效）；</li>
 *   <li>请求体不一致 → 以 {@code IDEMPOTENCY_KEY_MISMATCH} 拒绝。</li>
 * </ul>
 *
 * <p>状态迁移合法性由 {@link #transitionTo(ReleaseState, Instant)} 集中判定。
 */
@Entity
@Table(name = "release_order", indexes = {
        @Index(name = "uk_release_request", columnList = "requestId", unique = true),
        @Index(name = "idx_release_created", columnList = "createdAt,seq")
})
public class Release {

    private static final Map<ReleaseState, ReleaseState> ALLOWED_NEXT = Map.of(
            ReleaseState.DRAFT, ReleaseState.PENDING,
            ReleaseState.PENDING, ReleaseState.PUBLISHED,
            ReleaseState.PUBLISHED, ReleaseState.ROLLED_BACK
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(nullable = false, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReleaseState state;

    @Column(length = 500)
    private String remark;

    /** 后补字段：早期发布单没有格式代数概念，DDL 默认值为 1。 */
    @Column(nullable = false)
    private int formatVersion;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant publishedAt;

    private Instant rolledBackAt;

    /** 生效时间窗起点；null 表示"自发布起"。历史行该列为 NULL（无时间窗）。 */
    private Instant windowStart;

    /**
     * 生效时间窗终点；到期（windowEnd <= now 且状态为 PUBLISHED）触发自动回滚。
     * null 表示无到期点，永不参与自动回滚。历史行该列为 NULL。
     */
    private Instant windowEnd;

    /** "从发布起生效多久"的声明时长（秒）；发布瞬间据此算出 windowEnd。 */
    private Long windowDurationSeconds;

    /** 回滚来源；未回滚时为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RollbackReason rollbackReason;

    @OneToMany(mappedBy = "releaseSeq", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<ReleaseItem> items = new ArrayList<>();

    @Version
    private long optimisticVersion;

    protected Release() {
    }

    public Release(String requestId, String remark, Instant now) {
        this.requestId = requestId;
        this.remark = remark;
        this.state = ReleaseState.DRAFT;
        this.formatVersion = ConfigEntry.CURRENT_FORMAT_VERSION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 执行合法状态迁移。
     *
     * @throws IllegalStateException 目标迁移不在允许集合内
     */
    public void transitionTo(ReleaseState target, Instant now) {
        ReleaseState allowed = ALLOWED_NEXT.get(this.state);
        if (allowed != target) {
            throw new IllegalStateException("illegal transition " + this.state + " -> " + target);
        }
        this.state = target;
        this.updatedAt = now;
        if (target == ReleaseState.PUBLISHED) {
            this.publishedAt = now;
        } else if (target == ReleaseState.ROLLED_BACK) {
            this.rolledBackAt = now;
        }
    }

    public boolean canTransitionTo(ReleaseState target) {
        return ALLOWED_NEXT.get(this.state) == target;
    }

    /**
     * 设置/替换生效时间窗。三个参数整体替换旧值；
     * 全部传 null 等价于取消时间窗。仅允许在终态之前调用（服务层保证）。
     */
    public void applyWindow(Instant windowStart, Instant windowEnd, Long windowDurationSeconds) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.windowDurationSeconds = windowDurationSeconds;
    }

    /** 发布瞬间结算：若声明了时长而 windowEnd 尚未确定，则按发布时间推算。 */
    public void settleWindowOnPublish(Instant publishedAt) {
        if (this.windowEnd == null && this.windowDurationSeconds != null) {
            this.windowEnd = publishedAt.plusSeconds(this.windowDurationSeconds);
        }
    }

    /** 记录回滚来源（手动 / 到期自动）。 */
    public void markRollbackReason(RollbackReason reason) {
        this.rollbackReason = reason;
    }

    public void addItem(ReleaseItem item) {
        this.items.add(item);
    }

    public Long getSeq() {
        return seq;
    }

    public String getRequestId() {
        return requestId;
    }

    public ReleaseState getState() {
        return state;
    }

    public String getRemark() {
        return remark;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public Long getWindowDurationSeconds() {
        return windowDurationSeconds;
    }

    public RollbackReason getRollbackReason() {
        return rollbackReason;
    }

    public List<ReleaseItem> getItems() {
        return items;
    }

    public long getOptimisticVersion() {
        return optimisticVersion;
    }
}
