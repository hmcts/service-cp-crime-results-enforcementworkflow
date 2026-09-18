package uk.gov.hmcts.cp.gobsimulator.api;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import uk.gov.hmcts.cp.gobsimulator.api.model.ErrorResponse;

/**
 * Maps failures to the contract's {@code ErrorResponse} shape. Every error response the spec
 * defines (400/401/403/404/500) requires {@code errorCode} + {@code errorDescription} with
 * {@code additionalProperties: false}; Spring's default {@code ProblemDetail} body satisfies
 * neither, so before this advice existed every error the simulator produced broke the contract it
 * exists to emulate.
 *
 * <p>Deliberately narrow: only genuine client-input failures — a body that fails bean validation,
 * a body Jackson cannot parse (including an unrecognised property, since every request record is
 * annotated {@code @JsonIgnoreProperties(ignoreUnknown = false)}), or a {@code NowsDataItemName}
 * outside the contract's 12-value enum — map to 400. Everything else still surfaces as a 500, so
 * a genuine simulator defect is never hidden behind a client-error status; the fallback keeps
 * only the response *shape* contract-valid, with a description that never leaks a stack trace or
 * internal class name.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BAD_REQUEST_CODE = "BAD_REQUEST";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidationFailure(final MethodArgumentNotValidException exception) {
        log.info("Rejecting a request that failed bean validation: {}", exception.getMessage());
        return new ErrorResponse(BAD_REQUEST_CODE, "The request body failed validation.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadableBody(final HttpMessageNotReadableException exception) {
        log.info("Rejecting a request body that could not be parsed: {}", exception.getMessage());
        return new ErrorResponse(BAD_REQUEST_CODE, "The request body could not be parsed.");
    }

    @ExceptionHandler(UnknownNowsDataItemNameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnknownNowsDataItemName(final UnknownNowsDataItemNameException exception) {
        log.info("Rejecting a request naming an unknown NowsDataItemName: {}", exception.getMessage());
        return new ErrorResponse(BAD_REQUEST_CODE, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpectedFailure(final Exception exception) {
        log.error("Unexpected failure processing a request", exception);
        return new ErrorResponse(INTERNAL_ERROR_CODE, "An unexpected error occurred while processing the request.");
    }
}
