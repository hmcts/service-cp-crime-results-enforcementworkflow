package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One judicial result of the defendant, resolved (feature 002 research R19): the offence it belongs to ({@code offenceId},
 * null for a defendant-case or hearing-level result), its ids, its CP shortCode (null when reference data has no
 * definition), its GOB code (null when GOB does not accept it), whether it carries an adjournment marker
 * ({@code isAdjournmentResult} or a next hearing), and the date it was ordered.
 */
public record ResolvedResult(UUID offenceId, UUID judicialResultId, UUID judicialResultTypeId, String cpShortCode,
                             ResultCodeEnum gobCode, boolean adjournmentMarker, LocalDate orderedDate) {
}
