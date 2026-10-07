package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;

/**
 * 重新设置发布单生效时间窗的请求（整体替换旧窗口）。
 * 三个字段全为 null 等价于取消时间窗；语义与
 * {@link CreateReleaseRequest} 的窗口字段一致。
 */
public record UpdateWindowRequest(Instant effectiveFrom, Instant effectiveUntil,
                                  Long effectiveForSeconds) {
}
