package com.github.lisama569.versionedconfigreleasestorejava.domain;

/**
 * 回滚触发来源（可解释的变更记录依据）。
 *
 * <ul>
 *   <li>{@link #MANUAL}：人工调用 rollback；</li>
 *   <li>{@link #AUTO}：生效时间窗到期，由到期扫描自动回滚。</li>
 * </ul>
 *
 * <p>历史发布单（回滚于本特性上线前）该列为 NULL，读取时稳定归一化为 {@link #MANUAL}，
 * 与"旧系统只有人工回滚"这一事实保持一致。
 */
public enum RollbackTrigger {
    MANUAL,
    AUTO
}
