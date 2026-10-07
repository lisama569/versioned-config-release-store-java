package com.github.lisama569.versionedconfigreleasestorejava.domain;

import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;

import java.time.Instant;

/**
 * 校验通过后的生效时间窗值对象。
 *
 * <p>约束：
 * <ul>
 *   <li>{@code expireAt} 与 {@code ttlSeconds} 互斥；</li>
 *   <li>{@code ttlSeconds} 必须为正；</li>
 *   <li>绝对到期时刻必须晚于当前时刻；</li>
 *   <li>生效时刻若给出必须早于到期时刻（绝对窗口）；</li>
 *   <li>只有 effectiveAt 合法（表示"到点才生效、但不自动到期"）；三字段全空表示取消窗口，
 *       由 {@link #fromRequestOrNull} 表达为 null。</li>
 * </ul>
 */
public record WindowSpec(Instant effectiveAt, Instant expireAt, Long ttlSeconds) {

    public static WindowSpec fromRequestOrNull(WindowRequest request, Instant now) {
        if (request == null) {
            return null;
        }
        Instant eff = truncate(request.effectiveAt());
        Instant exp = truncate(request.expireAt());
        Long ttl = request.ttlSeconds();
        boolean allEmpty = eff == null && exp == null && ttl == null;
        if (allEmpty) {
            return null;
        }
        if (exp != null && ttl != null) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "expireAt and ttlSeconds are mutually exclusive");
        }
        if (ttl != null && ttl <= 0) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "ttlSeconds must be positive: " + ttl);
        }
        if (exp != null && !exp.isAfter(now)) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "expireAt must be in the future: " + exp + " <= now " + now);
        }
        if (eff != null && exp != null && !eff.isBefore(exp)) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "effectiveAt must be before expireAt: effectiveAt=" + eff + ", expireAt=" + exp);
        }
        return new WindowSpec(eff, exp, ttl);
    }

    private static Instant truncate(Instant i) {
        return i == null ? null
                : i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
}
