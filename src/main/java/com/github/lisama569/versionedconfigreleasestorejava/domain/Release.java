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

    /** 生效时刻（可空）：到达该时刻才允许/会被自动发布；NULL 表示不延迟生效。 */
    private Instant effectiveAt;

    /** 绝对到期时刻（可空）：到期自动回滚；NULL 表示无绝对到期。 */
    private Instant expireAt;

    /** 相对有效期秒数（可空）：发布时刻 + ttl = 实际到期时刻；NULL 表示非 TTL 窗口。 */
    private Long ttlSeconds;

    /** 回滚触发来源：MANUAL/AUTO；历史回滚行为 NULL，读取归一化为 MANUAL。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RollbackTrigger rollbackTrigger;

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

    /** 是否声明了生效时间窗（任意一个字段非空即算有窗口）。 */
    public boolean hasWindow() {
        return effectiveAt != null || expireAt != null || ttlSeconds != null;
    }

    /**
     * 配置/改期生效时间窗。调用方（service）负责语义校验与状态前置校验，
     * 这里只做赋值与时间戳维护。
     */
    public void configureWindow(Instant newEffectiveAt, Instant newExpireAt, Long newTtlSeconds,
                                Instant now) {
        this.effectiveAt = newEffectiveAt;
        this.expireAt = newExpireAt;
        this.ttlSeconds = newTtlSeconds;
        this.updatedAt = now;
    }

    /** 取消时间窗：此后与"从未设置时间窗"的发布单完全一致，永不自动回滚。 */
    public void clearWindow(Instant now) {
        this.effectiveAt = null;
        this.expireAt = null;
        this.ttlSeconds = null;
        this.updatedAt = now;
    }

    /**
     * 实际到期时刻：绝对窗口取 {@code expireAt}；TTL 窗口在发布后取
     * {@code publishedAt + ttlSeconds}，发布前尚无法确定（null）；无窗口恒为 null。
     */
    public Instant actualExpireAt() {
        if (expireAt != null) {
            return expireAt;
        }
        if (ttlSeconds != null && publishedAt != null) {
            return publishedAt.plusSeconds(ttlSeconds);
        }
        return null;
    }

    /** 是否已声明绝对到期时刻（到期扫描只处理这部分；TTL 待发布后固化）。 */
    public boolean hasConcreteDeadline() {
        return actualExpireAt() != null;
    }

    /** 回滚触发来源；历史回滚行（列为 NULL）稳定归一化为 MANUAL。 */
    public RollbackTrigger effectiveRollbackTrigger() {
        return rollbackTrigger == null ? RollbackTrigger.MANUAL : rollbackTrigger;
    }

    public void markRollbackTrigger(RollbackTrigger trigger) {
        this.rollbackTrigger = trigger;
    }

    /**
     * 发布成功后：TTL 窗口在此把相对有效期固化为绝对到期时刻，
     * 之后改期不再影响已确定的到期点；非 TTL 窗口不变。
     */
    public void materializeTtlDeadline(Instant publishInstant) {
        if (ttlSeconds != null && expireAt == null) {
            this.expireAt = publishInstant.plusSeconds(ttlSeconds);
        }
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

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public RollbackTrigger getRollbackTrigger() {
        return rollbackTrigger;
    }

    public List<ReleaseItem> getItems() {
        return items;
    }

    public long getOptimisticVersion() {
        return optimisticVersion;
    }
}
