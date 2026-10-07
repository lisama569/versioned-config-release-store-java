package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;

/**
 * 生效时间窗请求（创建/改期时使用）。三字段均可空，但 expireAt 与 ttlSeconds 互斥。
 *
 * @param effectiveAt 生效时刻（绝对）：到达该时刻才允许/自动发布；null 表示不延迟生效
 * @param expireAt    绝对到期时刻：到点自动回滚；与 ttlSeconds 互斥
 * @param ttlSeconds  相对有效期：发布时刻 + ttl = 实际到期时刻；与 expireAt 互斥
 */
public record WindowRequest(Instant effectiveAt, Instant expireAt, Long ttlSeconds) {
}
