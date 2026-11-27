package uk.gov.hmcts.cp.client;

/**
 * The {@code cpp-context-progression} Case Material attachment call (either leg) could not be answered (5xx,
 * timeout, unreachable, unexpected status) - feature 005 FR-020, contracts/progression-court-document.md. Always
 * retried, like every other external call in this codebase; never read as a confirmed failure on its own.
 */
public class ProgressionUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProgressionUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
