package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 把 {@link BusinessException} 映射为稳定的错误响应体。
 *
 * <p>响应固定包含 {@code errorCode}（调用方据此区分失败原因，不要解析 message）、
 * {@code message}（判定依据的可读说明）与时间戳。HTTP 状态码仅作粗分类，
 * 细粒度原因一律以 {@code errorCode} 为准。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        HttpStatus status = httpStatusOf(ex.getErrorCode());
        Map<String, Object> body = Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message", ex.getMessage(),
                "at", Instant.now().toString()
        );
        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus httpStatusOf(ErrorCode code) {
        return switch (code) {
            case CONFIG_NOT_FOUND, VERSION_NOT_FOUND, RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_CONFLICT, IDEMPOTENCY_KEY_CONFLICT, RELEASE_TARGET_MISMATCH,
                 ILLEGAL_TRANSITION -> HttpStatus.CONFLICT;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case PERSISTENCE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
