package uk.gov.hmcts.cp.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Identifies one share's submission for a case and defendant (feature 002 research R18/R19): the share correlation id
 * (hearing id + hearing day) plus the ids, caseUrn and share time recorded on every row.
 */
public record SubmissionKey(UUID correlationId, UUID hearingId, LocalDate hearingDay, UUID caseId, UUID defendantId,
                            String caseUrn, Instant sharedTime) {
}
