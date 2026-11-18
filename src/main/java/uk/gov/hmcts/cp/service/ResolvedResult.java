package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResultPrompt;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One judicial result of the defendant, resolved (feature 002 research R19): the offence it belongs to ({@code offenceId},
 * null for a defendant-case or hearing-level result), its ids, its CP shortCode (null when reference data has no
 * definition), its code sent to GOB, renamed where a rename exists (null when it cannot be sent: no shortCode, or too
 * long), whether it carries an adjournment marker ({@code isAdjournmentResult} or a next hearing), the date it was
 * ordered, and its prompts (feature 004, CIMD-4251: read for payment terms; never null, never logged).
 */
public record ResolvedResult(UUID offenceId, UUID judicialResultId, UUID judicialResultTypeId, String cpShortCode,
                             String gobCode, boolean adjournmentMarker, LocalDate orderedDate,
                             List<JudicialResultPrompt> prompts) {

    public ResolvedResult {
        prompts = prompts != null ? prompts.stream().filter(Objects::nonNull).toList() : List.of();
    }

    /** A result without prompts. */
    public ResolvedResult(final UUID offenceId, final UUID judicialResultId, final UUID judicialResultTypeId,
                          final String cpShortCode, final String gobCode, final boolean adjournmentMarker,
                          final LocalDate orderedDate) {
        this(offenceId, judicialResultId, judicialResultTypeId, cpShortCode, gobCode, adjournmentMarker, orderedDate, List.of());
    }

    /** Never prints prompt values (amounts, dates; FR-014): only how many prompts there are. */
    @Override
    public String toString() {
        return "ResolvedResult[offenceId=" + offenceId + ", judicialResultId=" + judicialResultId + ", judicialResultTypeId="
                + judicialResultTypeId + ", cpShortCode=" + cpShortCode + ", gobCode=" + gobCode + ", adjournmentMarker="
                + adjournmentMarker + ", orderedDate=" + orderedDate + ", prompts=" + prompts.size() + "]";
    }
}
