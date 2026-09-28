package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.ErrorResponse;

/**
 * Maps failures to the contract's {@code ErrorResponse} shape. Every error response the spec
 * defines (400/401/403/404/500) requires {@code errorCode} + {@code errorDescription} with
 * {@code additionalProperties: false}; Spring's default {@code ProblemDetail} body satisfies
 * neither, so before this advice existed every error the simulator produced broke the contract it
 * exists to emulate.
 *
 * <p>Deliberately narrow: only genuine client-input failures — a body that fails bean validation,
 * a body Jackson cannot parse (including an unrecognised property, since every request record is
 * annotated {@code @JsonIgnoreProperties(ignoreUnknown = false)}), a {@code NowsDataItemName}
 * outside the contract's 12-value enum, a {@code resultCode} outside the contract's {@code
 * resultCode} enum, an unknown URL, an unsupported HTTP method, or an unsupported {@code
 * Content-Type} — map to a client-error status. Everything else still surfaces as a 500, so a
 * genuine simulator defect is never hidden behind a client-error status; the fallback keeps only
 * the response *shape* contract-valid, with a description that never leaks a stack trace or
 * internal class name.
 *
 * <p>Finding I6: {@code @ExceptionHandler(Exception.class)} below is registered via {@code
 * ExceptionHandlerExceptionResolver}, which Spring runs BEFORE its own {@code
 * DefaultHandlerExceptionResolver} in the resolver chain — so, without the three handlers below,
 * a typo'd URL ({@link NoResourceFoundException}, framework 404), a wrong HTTP method ({@link
 * HttpRequestMethodNotSupportedException}, framework 405), and a wrong {@code Content-Type}
 * ({@link HttpMediaTypeNotSupportedException}, framework 415) were all caught by the catch-all
 * instead of ever reaching Spring's own resolvers, turning three routine client mistakes into 500s
 * with a full stack trace logged at ERROR into mandatory JSON stdout logging. Adding a specific
 * {@code @ExceptionHandler} for each preserves the framework's real status while still emitting
 * the contract's {@code ErrorResponse} body — {@code ExceptionHandlerMethodResolver} always
 * prefers the most specific matching handler over the {@code Exception.class} catch-all, so this
 * needs no change to resolver ordering.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BAD_REQUEST_CODE = "BAD_REQUEST";
    private static final String UNAUTHORIZED_CODE = "UNAUTHORIZED";
    private static final String NOT_FOUND_CODE = "NOT_FOUND";
    private static final String METHOD_NOT_ALLOWED_CODE = "METHOD_NOT_ALLOWED";
    private static final String UNSUPPORTED_MEDIA_TYPE_CODE = "UNSUPPORTED_MEDIA_TYPE";
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

    @ExceptionHandler(UnknownResultCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnknownResultCode(final UnknownResultCodeException exception) {
        log.info("Rejecting a request posting an unknown resultCode: {}", exception.getMessage());
        return new ErrorResponse(BAD_REQUEST_CODE, exception.getMessage());
    }

    /**
     * The description is the contract's own {@code Unauthorized} wording and is deliberately the
     * same whichever way the token failed — the reason is logged, never returned, so a caller
     * cannot use the response to tell an unissued token from an expired one.
     */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(final UnauthorizedException exception) {
        log.info("Rejecting a request to a secured endpoint: {}", exception.getMessage());
        return new ErrorResponse(UNAUTHORIZED_CODE, "Missing, expired or invalid bearer token.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNoResourceFound(final NoResourceFoundException exception) {
        log.info("Rejecting a request for an unknown resource: {}", exception.getMessage());
        return new ErrorResponse(NOT_FOUND_CODE, "The requested resource does not exist.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse handleMethodNotSupported(final HttpRequestMethodNotSupportedException exception) {
        log.info("Rejecting a request using an unsupported HTTP method: {}", exception.getMessage());
        return new ErrorResponse(METHOD_NOT_ALLOWED_CODE, "The HTTP method is not supported for this resource.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse handleUnsupportedMediaType(final HttpMediaTypeNotSupportedException exception) {
        log.info("Rejecting a request with an unsupported Content-Type: {}", exception.getMessage());
        return new ErrorResponse(UNSUPPORTED_MEDIA_TYPE_CODE, "The request's Content-Type is not supported.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpectedFailure(final Exception exception) {
        log.error("Unexpected failure processing a request", exception);
        return new ErrorResponse(INTERNAL_ERROR_CODE, "An unexpected error occurred while processing the request.");
    }
}
