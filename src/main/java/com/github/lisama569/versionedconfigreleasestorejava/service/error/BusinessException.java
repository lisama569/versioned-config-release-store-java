package com.github.lisama569.versionedconfigreleasestorejava.service.error;

/** 业务规则冲突异常，携带可区分的 {@link ConflictReason}。 */
public class BusinessException extends RuntimeException {

    private final ConflictReason reason;

    public BusinessException(ConflictReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ConflictReason getReason() {
        return reason;
    }
}
