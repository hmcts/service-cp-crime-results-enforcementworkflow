package uk.gov.hmcts.cp.client;

/**
 * The {@code enforcement_area} team-inbox lookup could not answer (5xx, timeout, unreachable, unexpected status) -
 * distinct from a confirmed 404 ("no such Division Code"), which is {@link java.util.Optional#empty()} and MUST NOT
 * collapse into this (feature 005 FR-006, contracts/enforcement-area.md): a transient failure is retried like every
 * other external call in this codebase, never read as {@code INBOX_NOT_FOUND}. Renamed from
 * {@code EnforcementAreaUnavailableException} (CCT-1222 merge) to avoid colliding with CIMD-4640's class of that name,
 * which covers an unrelated reference-data accounting-division-code lookup.
 */
public class TeamInboxUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TeamInboxUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
