package com.github.lisama569.versionedconfigreleasestorejava.error;

/**
 * 业务规则被违反时抛出。
 *
 * <p>每个失败都映射到唯一一个稳定 {@link ErrorCode}（可区分原因），并携带可读的
 * 判定依据（{@code message} 说明输入与判定逻辑）。调用方应依据 {@link #getErrorCode()}
 * 做分支处理，而不要解析 message 文本。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
