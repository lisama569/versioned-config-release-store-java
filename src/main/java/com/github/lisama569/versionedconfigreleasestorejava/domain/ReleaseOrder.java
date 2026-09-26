package com.github.lisama569.versionedconfigreleasestorejava.domain;

import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 发布工单：描述针对 {@code (configKey, version)} 的一次发布流程。
 *
 * <p>合法状态迁移（其余一律拒绝，错误码 {@code ILLEGAL_TRANSITION}）：
 * <pre>
 *   DRAFT     -> PENDING       提交待发布（幂等请求 requestId）
 *   PENDING   -> PUBLISHED     正式发布（幂等请求 requestId）
 *   PUBLISHED -> ROLLED_BACK   回滚到上一个已发布版本（幂等请求 requestId）
 *   PENDING   -> ROLLED_BACK   待发布撤销（取消尚未生效的发布）
 * </pre>
 * DRAFT 不允许直接发布或回滚；PUBLISHED 不能重复发布；ROLLED_BACK 为终态。
 *
 * <p>{@code seq} 为全局写入序号，用于分页排序决胜；状态迁移本身不在此对象加锁，
 * 由服务层在单一临界区内完成“校验 + 迁移 + 落盘”。
 */
public final class ReleaseOrder {

    private final String orderId;
    private final String configKey;
    private final long version;
    private final Instant createdAt;
    private final long seq;

    private ReleaseStatus status;
    private Instant updatedAt;
    private String submitRequestId;
    private String publishRequestId;
    private String rollbackRequestId;

    public ReleaseOrder(String orderId,
                        String configKey,
                        long version,
                        Instant createdAt,
                        long seq) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.configKey = Objects.requireNonNull(configKey, "configKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.seq = seq;
        this.status = ReleaseStatus.DRAFT;
        this.updatedAt = createdAt;
    }

    /**
     * 恢复历史工单时使用的完整构造器（含全部字段），服务于崩溃恢复与兼容性补字段。
     */
    public static ReleaseOrder restore(String orderId,
                                       String configKey,
                                       long version,
                                       ReleaseStatus status,
                                       Instant createdAt,
                                       Instant updatedAt,
                                       long seq,
                                       String submitRequestId,
                                       String publishRequestId,
                                       String rollbackRequestId) {
        ReleaseOrder o = new ReleaseOrder(orderId, configKey, version, createdAt, seq);
        o.status = Objects.requireNonNull(status, "status");
        o.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        o.submitRequestId = submitRequestId;
        o.publishRequestId = publishRequestId;
        o.rollbackRequestId = rollbackRequestId;
        return o;
    }

    private void requireStatus(ReleaseStatus expected, String action) {
        if (this.status != expected) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    "非法状态迁移: orderId=" + orderId + ", 动作=" + action
                            + ", 当前状态=" + status + ", 期望状态=" + expected);
        }
    }

    /** DRAFT -> PENDING。重复提交同一 requestId 由服务层幂等处理，不重复生效。 */
    public void markPending(String requestId, Instant at) {
        requireStatus(ReleaseStatus.DRAFT, "submit");
        this.status = ReleaseStatus.PENDING;
        this.submitRequestId = Objects.requireNonNull(requestId, "requestId");
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** PENDING -> PUBLISHED。 */
    public void markPublished(String requestId, Instant at) {
        requireStatus(ReleaseStatus.PENDING, "publish");
        this.status = ReleaseStatus.PUBLISHED;
        this.publishRequestId = Objects.requireNonNull(requestId, "requestId");
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** PUBLISHED -> ROLLED_BACK 或 PENDING -> ROLLED_BACK（撤销未生效发布）。 */
    public void markRolledBack(String requestId, Instant at) {
        if (this.status != ReleaseStatus.PUBLISHED && this.status != ReleaseStatus.PENDING) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    "非法状态迁移: orderId=" + orderId + ", 动作=rollback"
                            + ", 当前状态=" + status + ", 仅允许 PENDING/PUBLISHED 回滚");
        }
        this.status = ReleaseStatus.ROLLED_BACK;
        this.rollbackRequestId = Objects.requireNonNull(requestId, "requestId");
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    public String getOrderId() {
        return orderId;
    }

    public String getConfigKey() {
        return configKey;
    }

    public long getVersion() {
        return version;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getSeq() {
        return seq;
    }

    public String getSubmitRequestId() {
        return submitRequestId;
    }

    public String getPublishRequestId() {
        return publishRequestId;
    }

    public String getRollbackRequestId() {
        return rollbackRequestId;
    }
}
