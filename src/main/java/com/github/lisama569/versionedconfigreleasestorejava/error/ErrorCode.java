package com.github.lisama569.versionedconfigreleasestorejava.error;

/**
 * 可区分的业务失败原因。每个失败必须映射到唯一一个稳定错误码。
 */
public enum ErrorCode {
    CONFIG_NOT_FOUND,
    VERSION_NOT_FOUND,
    VERSION_CONFLICT,
    RELEASE_NOT_FOUND,
    ILLEGAL_TRANSITION,
    IDEMPOTENCY_KEY_CONFLICT,
    RELEASE_TARGET_MISMATCH,
    VALIDATION_ERROR,
    PERSISTENCE_ERROR
}
