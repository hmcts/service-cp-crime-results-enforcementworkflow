package uk.gov.hmcts.cp.client;

/**
 * The {@code enforcement_area} lookup could not answer (5xx, timeout, unreachable, unexpected status) - distinct from
 * a confirmed 404 ("no such Division Code"), which is {@link java.util.Optional#empty()} and MUST NOT collapse into
 * this (feature 005 FR-006, contracts/enforcement-area.md): a transient failure is retried like every other external
 * call in this codebase, never read as {@code INBOX_NOT_FOUND}.
 */
public class EnforcementAreaUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnforcementAreaUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
