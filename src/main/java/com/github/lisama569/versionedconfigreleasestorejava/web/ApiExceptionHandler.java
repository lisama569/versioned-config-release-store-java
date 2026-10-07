package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** ENTRY_NOT_FOUND / RELEASE_NOT_FOUND 语义上是 404；INVALID_WINDOW 是 400；其余业务冲突为 409。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ConflictReason reason = ex.getReason();
        HttpStatus status;
        if (reason == ConflictReason.ENTRY_NOT_FOUND || reason == ConflictReason.RELEASE_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
        } else if (reason == ConflictReason.INVALID_WINDOW) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            status = HttpStatus.CONFLICT;
        }
        log.warn("business rejection: reason={}, status={}, message={}",
                reason.name(), status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "code", reason.name(),
                "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        log.warn("bad request: message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "code", "INVALID_REQUEST",
                "message", String.valueOf(ex.getMessage())));
    }
}
