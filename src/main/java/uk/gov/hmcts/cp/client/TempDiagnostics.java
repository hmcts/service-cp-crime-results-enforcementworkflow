package uk.gov.hmcts.cp.client;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.RestClientResponseException;

/**
 * TEMP DIAGNOSTICS (test environments only, as in the enforcement gateway): helpers for the extra
 * error details logged on outbound call failures so QA can see why a call failed. Remove this class
 * and the lines marked "TEMP DIAGNOSTICS" in the clients before release to higher environments.
 */
final class TempDiagnostics {

    private TempDiagnostics() {
    }

    /** The deepest cause, e.g. the {@code ConnectException} inside a {@code ResourceAccessException}. */
    /* default */ static Throwable rootCause(final Throwable error) {
        return NestedExceptionUtils.getMostSpecificCause(error);
    }

    /**
     * Same stack trace as the original, but a message with the status only: the original message (and so
     * its "Caused by" chain) embeds the response body, which can echo payload values.
     */
    /* default */ static Throwable withoutResponseBody(final RestClientResponseException error) {
        final RuntimeException safeCopy = new RuntimeException(
                error.getClass().getName() + ": HTTP " + error.getStatusCode().value() + " " + error.getStatusText());
        safeCopy.setStackTrace(error.getStackTrace());
        return safeCopy;
    }

    /* default */ static Object contentType(final RestClientResponseException error) {
        return error.getResponseHeaders() == null ? null : error.getResponseHeaders().getContentType();
    }
}
