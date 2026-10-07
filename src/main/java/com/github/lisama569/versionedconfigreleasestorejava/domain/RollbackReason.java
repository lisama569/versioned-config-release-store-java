package com.github.lisama569.versionedconfigreleasestorejava.domain;

/**
 * 回滚来源。每次回滚（无论手动还是时间窗到期自动触发）都会记录，
 * 使"已经被自动回滚"等时间窗维度查询可追溯、可解释。
 */
public enum RollbackReason {
    /** 人工调用 rollback 触发 */
    MANUAL,
    /** 生效时间窗到期，由到期清扫自动触发 */
    AUTO_EXPIRED
}
