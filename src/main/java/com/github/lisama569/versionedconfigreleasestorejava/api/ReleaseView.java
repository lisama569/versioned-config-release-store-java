package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;
import java.util.List;

/**
 * 发布单视图。
 *
 * @param effectiveAt       生效时刻，null=不延迟生效
 * @param expireAt          绝对到期时刻（TTL 窗口在发布前为 null）
 * @param ttlSeconds        相对有效期秒数，null=非 TTL 窗口
 * @param actualExpireAt    实际到期时刻：expireAt 或 publishedAt+ttl；无窗口为 null
 * @param rollbackTrigger   回滚触发来源 MANUAL/AUTO；未回滚为 null；历史回滚行稳定归一化为 MANUAL
 */
public record ReleaseView(long seq, String requestId, String state, List<String> entryKeys,
                          String remark, Long publishedRevisionSeq, int formatVersion,
                          Instant createdAt, Instant updatedAt,
                          Instant effectiveAt, Instant expireAt, Long ttlSeconds,
                          Instant actualExpireAt, String rollbackTrigger) {

    /** 无时间窗信息时的兼容构造器（时间窗相关字段全为 null）。 */
    public ReleaseView(long seq, String requestId, String state, List<String> entryKeys,
                       String remark, Long publishedRevisionSeq, int formatVersion,
                       Instant createdAt, Instant updatedAt) {
        this(seq, requestId, state, entryKeys, remark, publishedRevisionSeq, formatVersion,
                createdAt, updatedAt, null, null, null, null, null);
    }
}
