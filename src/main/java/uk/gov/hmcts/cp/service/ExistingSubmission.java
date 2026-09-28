package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.entity.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/** The existing submission for a hearing/case/defendant, if any (research.md R17/R19). */
public record ExistingSubmission(UUID id, SubmissionStatus status, Instant updatedAt) {
}
