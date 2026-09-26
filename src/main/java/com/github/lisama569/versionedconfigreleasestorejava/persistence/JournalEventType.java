package com.github.lisama569.versionedconfigreleasestorejava.persistence;

/**
 * 日志事件类型。仅允许新增枚举值，不得重排或复用既有值的语义（兼容规则）。
 */
public enum JournalEventType {
    CONFIG_CREATED,
    CONFIG_UPDATED,
    RELEASE_CREATED,
    RELEASE_STATUS_CHANGED
}
