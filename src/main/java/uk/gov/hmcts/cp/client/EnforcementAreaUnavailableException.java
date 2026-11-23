package uk.gov.hmcts.cp.client;

/**
 * Reference data could not answer an enforcement area lookup (5xx, timeout, unreachable, unexpected status), CIMD-4640.
 * Unlike {@link ReferenceDataUnavailableException} it is thrown after GOB has replied, so the notification's stage 1 is
 * retried (GOB is not contacted again for the same notification). It names the lookup's parameter and the failure's
 * class only, and keeps no cause: the cause's message can quote the url, which carries the defendant's postcode (FR-017).
 */
public class EnforcementAreaUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnforcementAreaUnavailableException(final String parameter, final Throwable cause) {
        super("Enforcement area lookup by " + parameter + " failed: " + cause.getClass().getSimpleName());
    }
}
