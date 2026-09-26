package com.github.lisama569.versionedconfigreleasestorejava.support;

import java.time.Instant;

/**
 * 基于系统 UTC 时钟的默认实现。
 */
public class SystemClock implements Clock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
