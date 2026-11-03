package uk.gov.hmcts.cp.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;
import uk.gov.hmcts.cp.service.NotificationRejectedException;

import java.time.Instant;

/**
 * Error responses of the notification endpoint (feature 003 contract): 400 invalid body, 403 missing or not
 * allow-listed CJSCPPUID, 409 inconsistent repeat, 503 storage unavailable. Messages never echo payload values.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = EnforcementNotificationController.class)
public class NotificationExceptionHandler {

    private static final String CJSCPPUID = "CJSCPPUID";

    @ExceptionHandler(NotificationRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(final NotificationRejectedException e) {
        final HttpStatus status = switch (e.getReason()) {
            case CALLER_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        log.warn("Enforcement notification rejected: {} ({})", e.getReason(), status.value());
        // TEMP DIAGNOSTICS: the full reason
        TempPayloadLog.warn("Enforcement notification rejected: {} (HTTP {}): {}", e.getReason(), status.value(), e.getMessage());
        return error(status, e.getReason().name(), e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(final MissingRequestHeaderException e) {
        return CJSCPPUID.equals(e.getHeaderName()) ? forbidden() : invalid("missing header " + e.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(final MethodArgumentTypeMismatchException e) {
        return CJSCPPUID.equals(e.getName()) ? forbidden() : invalid("invalid " + e.getName());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> invalidBody(final Exception e) {
        log.warn("Enforcement notification rejected: invalid body ({})", e.getClass().getSimpleName());
        // TEMP DIAGNOSTICS: what was wrong with the body (field errors or the parser's message)
        TempPayloadLog.warn("Enforcement notification rejected: invalid body: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        return invalid("invalid enforcement notification");
    }

    // TransactionException: the database is down before any statement runs (no connection for the transaction)
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> unavailable(final RuntimeException e) {
        log.error("Enforcement notification not stored: {}", e.getClass().getSimpleName());
        // TEMP DIAGNOSTICS: message, root cause and stack trace
        TempPayloadLog.error("Enforcement notification not stored: {} - {} (rootCause={})", e.getClass().getSimpleName(),
                e.getMessage(), NestedExceptionUtils.getMostSpecificCause(e).toString(), e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "notification could not be stored; retry");
    }

    private ResponseEntity<ErrorResponse> forbidden() {
        log.warn("Enforcement notification rejected: CJSCPPUID missing or invalid (403)");
        return error(HttpStatus.FORBIDDEN, "CALLER_NOT_ALLOWED", "CJSCPPUID is not an allowed system user");
    }

    private static ResponseEntity<ErrorResponse> invalid(final String message) {
        return error(HttpStatus.BAD_REQUEST, "INVALID", message);
    }

    private static ResponseEntity<ErrorResponse> error(final HttpStatus status, final String code, final String message) {
        return ResponseEntity.status(status).body(new ErrorResponse().error(code).message(message).timestamp(Instant.now()));
    }
}
