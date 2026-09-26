package com.github.lisama569.versionedconfigreleasestorejava.domain;

/** 配置变更类型。 */
public enum ChangeType {
    /** 首次创建条目 */
    CREATE,
    /** 普通修改 */
    UPDATE,
    /** 发布回滚恢复旧值而追加的版本记录 */
    RESTORE
}
