package com.github.lisama569.versionedconfigreleasestorejava.service;

import java.time.Instant;

/**
 * 集中式时间源。默认取系统 UTC 时间；测试可通过 {@link #fixedAt(Instant)}
 * 注入确定时间，使同一毫秒内的排序仍由单调 seq 决胜。
 */
public final class Clock {

    private static volatile java.time.Clock delegate = java.time.Clock.systemUTC();

    private Clock() {
    }

    public static Instant now() {
        // 统一截断到毫秒：H2 TIMESTAMP WITH TIME ZONE 只存微秒，
        // 若写入带纳秒的值，读回后精度变化会让"重放响应逐字段一致"不稳定。
        return Instant.now(delegate).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    public static void fixedAt(Instant instant) {
        delegate = java.time.Clock.fixed(
                instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                java.time.ZoneOffset.UTC);
    }

    public static void reset() {
        delegate = java.time.Clock.systemUTC();
    }
}
