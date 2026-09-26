package com.github.lisama569.versionedconfigreleasestorejava.store;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseOrder;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布工单存储 + 幂等键登记。
 *
 * <p>{@code idempotency} 记录每个 {@code requestId} 首次成功执行时所对应的动作与结果。
 * 重复请求命中登记后直接重放首次结论，不再次触发状态迁移，因此：
 * <ul>
 *   <li>不会重复生效（状态迁移只发生一次）；</li>
 *   <li>同键复用于不同动作/工单会得到 {@code IDEMPOTENCY_KEY_CONFLICT}，与“成功重放”可区分。</li>
 * </ul>
 *
 * <p>变更方法均假定调用方已持有 {@link StoreCoordinator} 写锁。
 */
public final class ReleaseStore {

    /** 幂等动作类型，登记 requestId 时同时记录，防止同一个键被复用于不同语义的请求。 */
    public enum IdempotentAction {
        CREATE_DRAFT, SUBMIT, PUBLISH, ROLLBACK
    }

    /** 一次幂等请求的首次执行凭证。 */
    public static final class IdempotencyRecord {
        private final IdempotentAction action;
        private final String orderId;
        private final ReleaseStatus resultingStatus;
        private final Instant completedAt;

        public IdempotencyRecord(IdempotentAction action,
                                 String orderId,
                                 ReleaseStatus resultingStatus,
                                 Instant completedAt) {
            this.action = Objects.requireNonNull(action, "action");
            this.orderId = Objects.requireNonNull(orderId, "orderId");
            this.resultingStatus = Objects.requireNonNull(resultingStatus, "resultingStatus");
            this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }

        public IdempotentAction getAction() {
            return action;
        }

        public String getOrderId() {
            return orderId;
        }

        public ReleaseStatus getResultingStatus() {
            return resultingStatus;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }
    }

    private final Map<String, ReleaseOrder> orders = new ConcurrentHashMap<>();
    private final TreeMap<Long, ReleaseOrder> bySeq = new TreeMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();

    // ---- 写（锁内调用） -------------------------------------------------

    public void restoreOrder(ReleaseOrder order) {
        Objects.requireNonNull(order, "order");
        orders.put(order.getOrderId(), order);
        bySeq.put(order.getSeq(), order);
    }

    /** 恢复幂等登记（重放状态迁移事件时补齐）。 */
    public void restoreIdempotency(String requestId, IdempotencyRecord record) {
        idempotency.put(Objects.requireNonNull(requestId, "requestId"),
                Objects.requireNonNull(record, "record"));
    }

    public void addOrder(ReleaseOrder order) {
        Objects.requireNonNull(order, "order");
        ReleaseOrder previous = orders.put(order.getOrderId(), order);
        if (previous != null) {
            throw new IllegalStateException("orderId 重复: " + order.getOrderId());
        }
        bySeq.put(order.getSeq(), order);
    }

    public void putIdempotency(String requestId, IdempotencyRecord record) {
        idempotency.put(Objects.requireNonNull(requestId, "requestId"),
                Objects.requireNonNull(record, "record"));
    }

    // ---- 读（无锁快照） -------------------------------------------------

    public Optional<ReleaseOrder> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public Optional<IdempotencyRecord> findIdempotency(String requestId) {
        return Optional.ofNullable(idempotency.get(requestId));
    }

    /**
     * 某配置 key 当前实际生效的已发布版本：所有状态为 PUBLISHED 的工单中版本号最大者；
     * 没有则返回 0（从未有生效版本）。
     *
     * <p>允许同一 key 同时存在多个 PUBLISHED 工单（旧发布不会被自动改写状态），
     * 回滚某个工单后在此处重新取最大值，即得到“上一个仍生效的已发布版本”。
     */
    public long activePublishedVersion(String configKey) {
        long max = 0L;
        for (ReleaseOrder o : orders.values()) {
            if (o.getConfigKey().equals(configKey) && o.getStatus() == ReleaseStatus.PUBLISHED) {
                max = Math.max(max, o.getVersion());
            }
        }
        return max;
    }

    /** 某 key 的全部工单，按写入 seq 升序的不可变快照。 */
    public List<ReleaseOrder> ordersOf(String configKey) {
        List<ReleaseOrder> out = new ArrayList<>();
        for (ReleaseOrder o : bySeq.values()) {
            if (o.getConfigKey().equals(configKey)) {
                out.add(o);
            }
        }
        return List.copyOf(out);
    }

    /** seq 严格大于 {@code afterSeqExclusive} 的工单快照，按 seq 升序，至多 limit 条。 */
    public List<ReleaseOrder> ordersAfter(long afterSeqExclusive, int limit) {
        List<ReleaseOrder> out = new ArrayList<>();
        for (ReleaseOrder o : bySeq.tailMap(afterSeqExclusive + 1, true).values()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(o);
        }
        return List.copyOf(out);
    }

    public int orderCount() {
        return orders.size();
    }
}
