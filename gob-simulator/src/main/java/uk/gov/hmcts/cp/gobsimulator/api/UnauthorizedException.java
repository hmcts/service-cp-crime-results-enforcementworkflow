package uk.gov.hmcts.cp.gobsimulator.api;

/**
 * Thrown when a request to a secured endpoint carries no usable bearer token. Mapped by {@link
 * GlobalExceptionHandler} to 401 + the contract's {@code ErrorResponse}, matching the spec's
 * {@code Unauthorized} response — "Missing, expired or invalid bearer token."
 *
 * <p>The message names the reason for the log line only. It is never returned to the caller: the
 * response body says no more than that the token was unusable, so a caller cannot probe the
 * difference between a token that was never issued and one that has expired.
 */
public class UnauthorizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(final String reason) {
        super(reason);
    }
}
