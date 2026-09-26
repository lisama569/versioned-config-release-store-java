package com.github.lisama569.versionedconfigreleasestorejava.domain;

/**
 * 发布单状态机。合法迁移仅有三条：
 * <pre>
 *   DRAFT -> PENDING -> PUBLISHED -> ROLLED_BACK
 * </pre>
 * 其余一切迁移都以 {@code ILLEGAL_STATE_TRANSITION} 拒绝。
 * ROLLED_BACK 为终态，不允许再发布或再次回滚。
 */
public enum ReleaseState {
    DRAFT,
    PENDING,
    PUBLISHED,
    ROLLED_BACK
}
