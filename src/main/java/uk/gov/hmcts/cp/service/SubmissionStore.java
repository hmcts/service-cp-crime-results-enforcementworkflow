package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.mapper.MappingFailureReason;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists hearing-result submissions (data-model.md §3). Each write is its own short transaction, so
 * no transaction is held open across the HTTP call to the gateway. The unique key
 * {@code (hearing_id, case_id, defendant_id)} enforces at most one submission per first share
 * (FR-005, constitution Principle III).
 */
@Component
@RequiredArgsConstructor
public class SubmissionStore {

    public static final String OUTCOME_UNKNOWN_INTERRUPTED = "INTERRUPTED – outcome at GOB unknown";
    private static final int CASE_URN_MAX = 36;

    private final HearingResultSubmissionRepository repository;
    private final HearingResultProperties properties;
    private final Clock clock;

    public Optional<ExistingSubmission> findExisting(final UUID hearingId, final UUID caseId, final UUID defendantId) {
        return repository.findByHearingIdAndCaseIdAndDefendantId(hearingId, caseId, defendantId)
                .map(e -> new ExistingSubmission(e.getId(), e.getStatus(), e.getUpdatedAt()));
    }

    /**
     * A SENDING row older than the stale threshold: its call was interrupted (research.md R19). It is marked
     * FAILED with an unknown outcome and never resent. A final row, or a younger SENDING row (a call that may
     * still be in flight), is not stale.
     */
    public boolean isStale(final ExistingSubmission existing) {
        return existing.status() == SubmissionStatus.SENDING
                && existing.updatedAt().isBefore(Instant.now(clock).minus(properties.getStaleSendingThreshold()));
    }

    /**
     * Inserts the SENDING row. Returns empty when a row for the same hearing/case/defendant already exists
     * (a concurrent or repeated delivery). Deliberately not {@code @Transactional}: the repository's own
     * transaction rolls back on the unique-key violation, and the violation is translated here.
     */
    public Optional<UUID> recordSending(final UUID hearingId, final UUID caseId, final UUID defendantId, final String caseUrn,
                                        final Instant sharedTime, final String requestJson) {
        final HearingResultSubmissionEntity entity = newRow(hearingId, caseId, defendantId, caseUrn, sharedTime, SubmissionStatus.SENDING);
        entity.setRequestPayload(requestJson);
        return insertIfAbsent(entity);
    }

    /** Unsendable: the request could not be built (FR-013). There is no request payload; {@code detail} carries no PII. */
    public void recordMappingFailed(final UUID hearingId, final UUID caseId, final UUID defendantId, final String caseUrn,
                                    final Instant sharedTime, final MappingFailureReason reason, final String detail) {
        final HearingResultSubmissionEntity entity = newRow(hearingId, caseId, defendantId, caseUrn, sharedTime,
                SubmissionStatus.MAPPING_FAILED);
        entity.setErrorDetail(reason + ": " + detail);
        insertIfAbsent(entity);
    }

    /** None of the defendant's results is a code GOB accepts (FR-009/FR-013). */
    public void recordSkippedNoResultCode(final UUID hearingId, final UUID caseId, final UUID defendantId, final String caseUrn,
                                          final Instant sharedTime, final List<String> droppedCodes) {
        final HearingResultSubmissionEntity entity = newRow(hearingId, caseId, defendantId, caseUrn, sharedTime,
                SubmissionStatus.SKIPPED_NO_RESULT_CODE);
        entity.setErrorDetail("no GOB-recognised result code; dropped " + droppedCodes);
        insertIfAbsent(entity);
    }

    /**
     * Nothing was sent to GOB because something CP depends on failed before the call (e.g. reference data
     * unavailable). The outcome is known, so the row is FAILED with the reason and no request payload.
     */
    public void recordNotSent(final UUID hearingId, final UUID caseId, final UUID defendantId, final String caseUrn,
                              final Instant sharedTime, final String detail) {
        final HearingResultSubmissionEntity entity = newRow(hearingId, caseId, defendantId, caseUrn, sharedTime, SubmissionStatus.FAILED);
        entity.setErrorDetail("NOT_SENT – " + detail);
        insertIfAbsent(entity);
    }

    /** The gateway rejected or failed the call. {@code httpStatus} is null when no response was received. No retry (R12). */
    @Transactional
    public void recordFailed(final UUID id, final Integer httpStatus, final String detail) {
        final HearingResultSubmissionEntity entity = repository.findById(id).orElseThrow();
        entity.setStatus(SubmissionStatus.FAILED);
        entity.setHttpStatus(httpStatus);
        entity.setErrorDetail(detail);
    }

    public List<UUID> findStaleSending(final Instant cutoff) {
        return repository.findIdsByStatusUpdatedBefore(SubmissionStatus.SENDING, cutoff);
    }

    /** A stale SENDING row becomes FAILED with an unknown outcome, and is never resent (research.md R19). */
    @Transactional
    public void markInterrupted(final UUID id) {
        repository.failIfSending(id, OUTCOME_UNKNOWN_INTERRUPTED, Instant.now(clock), SubmissionStatus.FAILED, SubmissionStatus.SENDING);
    }

    private static HearingResultSubmissionEntity newRow(final UUID hearingId, final UUID caseId, final UUID defendantId,
                                                        final String caseUrn, final Instant sharedTime, final SubmissionStatus status) {
        final HearingResultSubmissionEntity entity = new HearingResultSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setHearingId(hearingId);
        entity.setCaseId(caseId);
        entity.setDefendantId(defendantId);
        // a caseUrn over 36 characters is never truncated (Principle VI): the row keeps an empty value instead (also for null)
        entity.setCaseUrn(caseUrn != null && caseUrn.length() <= CASE_URN_MAX ? caseUrn : "");
        entity.setSharedTime(sharedTime);
        entity.setStatus(status);
        return entity;
    }

    /**
     * Inserts the row, or returns empty when a row for the same hearing/case/defendant already exists (the
     * unique key). Any other integrity violation (e.g. a NOT NULL column) is a defect, not a duplicate. It is
     * rethrown without the database message, which can contain the payload (PII).
     */
    private Optional<UUID> insertIfAbsent(final HearingResultSubmissionEntity entity) {
        Optional<UUID> id;
        try {
            id = Optional.of(repository.saveAndFlush(entity).getId());
        } catch (DataIntegrityViolationException e) {
            if (findExisting(entity.getHearingId(), entity.getCaseId(), entity.getDefendantId()).isEmpty()) {
                throw new IllegalStateException("Could not record " + entity.getStatus() + " submission for hearing "
                        + entity.getHearingId() + " case " + entity.getCaseId() + " defendant " + entity.getDefendantId()
                        + " (integrity violation other than the unique key)");
            }
            id = Optional.empty();
        }
        return id;
    }

    /**
     * GOB accepted the submission (a 2xx). The reply is kept even when it isn't JSON (research.md R24):
     * {@code response_payload} is jsonb, so such a reply is stored as a JSON string, and a blank one as null.
     */
    @Transactional
    public void recordSucceeded(final UUID id, final String responseBody, final int httpStatus) {
        final HearingResultSubmissionEntity entity = repository.findById(id).orElseThrow();
        entity.setStatus(SubmissionStatus.SUCCEEDED);
        entity.setResponsePayload(asJsonb(responseBody));
        entity.setHttpStatus(httpStatus);
    }

    private static String asJsonb(final String body) {
        String json = null;
        if (body != null && !body.isBlank()) {
            try {
                PayloadJson.MAPPER.readTree(body);
                json = body;
            } catch (JacksonException e) {
                json = PayloadJson.MAPPER.writeValueAsString(body);
            }
        }
        return json;
    }
}
