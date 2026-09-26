package com.github.lisama569.versionedconfigreleasestorejava.service.error;

/**
 * 可区分的失败原因。每种原因对应稳定的错误码，便于调用方与测试精确判定。
 */
public enum ConflictReason {
    /** 条目不存在 */
    ENTRY_NOT_FOUND,
    /** 创建条目时 key 已存在（并发下由唯一索引兜底） */
    ENTRY_KEY_DUPLICATE,
    /** 乐观锁版本不匹配：基于过期版本并发修改 */
    VERSION_CONFLICT,
    /** 条件更新携带的期望值与现值不一致 */
    VALUE_MISMATCH,
    /** 发布单不存在 */
    RELEASE_NOT_FOUND,
    /** 发布单当前状态不允许执行目标迁移 */
    ILLEGAL_STATE_TRANSITION,
    /** 发布单不包含任何条目 */
    EMPTY_RELEASE,
    /** 目标条目未处于可发布/可回滚的状态 */
    ENTRY_NOT_RELEASABLE,
    /** 回滚前置条件不满足（如发布后条目又被改动） */
    ROLLBACK_PRECONDITION_FAILED,
    /** 幂等键重复，但请求体与首次请求不一致 */
    IDEMPOTENCY_KEY_MISMATCH
}
