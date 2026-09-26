package com.github.lisama569.versionedconfigreleasestorejava.domain;

/**
 * 发布状态。合法迁移在服务层定义。
 */
public enum ReleaseStatus {
    DRAFT,
    PENDING,
    PUBLISHED,
    ROLLED_BACK
}
