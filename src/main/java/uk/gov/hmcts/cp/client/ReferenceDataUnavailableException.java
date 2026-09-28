package uk.gov.hmcts.cp.client;

/**
 * Reference data could not answer a shortCode lookup (5xx, timeout, unreachable, unexpected status).
 * Nothing has been sent to GOB at that point, so the outcome is known: the submission is recorded as
 * not sent (spec FR-013). This is distinct from a 404, which means "no such result definition".
 */
public class ReferenceDataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReferenceDataUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
