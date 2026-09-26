package com.github.lisama569.versionedconfigreleasestorejava.support;

import java.time.Instant;

/**
 * 时间来源抽象，便于测试中固定时间。
 */
public interface Clock {
    Instant now();
}
