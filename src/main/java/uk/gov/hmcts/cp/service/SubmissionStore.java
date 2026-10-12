package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.GobSentOffenceEntity;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.mapper.MappingFailureReason;
import uk.gov.hmcts.cp.repository.GobSentOffenceRepository;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists hearing-result submissions (data-model.md §3). Each write is its own short transaction, so
 * no transaction is held open across the HTTP call to the gateway. Two unique keys enforce idempotency
 * (constitution Principle III): {@code (correlation_id, case_id, defendant_id)}, at most one submission per
 * share (feature 002 research R18/R19), and the sent-offence keys of {@code gob_sent_offence}, each offence
 * (or result not attached to an offence) sent to GOB at most once (FR-018).
 */
@Component
@RequiredArgsConstructor
public class SubmissionStore {

    public static final String OUTCOME_UNKNOWN_INTERRUPTED = "INTERRUPTED – outcome at GOB unknown";
    private static final int CASE_URN_MAX = 36;

    private final HearingResultSubmissionRepository repository;
    private final GobSentOffenceRepository sentOffenceRepository;
    private final HearingResultProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public Optional<ExistingSubmission> findExisting(final UUID correlationId, final UUID caseId, final UUID defendantId) {
        return repository.findByCorrelationIdAndCaseIdAndDefendantId(correlationId, caseId, defendantId)
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
     * Inserts the SENDING row and the items it sends ({@code gob_sent_offence}) in one transaction, so an item is
     * recorded as sent exactly when its submission is. {@link SendingRecord.AlreadyRecorded} when a row for the
     * same share, case and defendant exists (a concurrent or repeated delivery); {@link SendingRecord.ItemsAlreadySent}
     * when another share recorded one of the items meanwhile (nothing is recorded; the caller filters again).
     */
    public SendingRecord recordSending(final SubmissionKey key, final String requestJson, final List<SentItem> sentItems) {
        final HearingResultSubmissionEntity entity = newRow(key, SubmissionStatus.SENDING);
        entity.setRequestPayload(requestJson);
        SendingRecord record;
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repository.saveAndFlush(entity);
                sentItems.forEach(item -> sentOffenceRepository.saveAndFlush(sentItemRow(key, entity.getId(), item)));
            });
            record = new SendingRecord.Recorded(entity.getId());
        } catch (DataIntegrityViolationException e) {
            if (findExisting(key.correlationId(), key.caseId(), key.defendantId()).isPresent()) {
                record = new SendingRecord.AlreadyRecorded();
            } else if (anyAlreadySent(key.caseId(), sentItems)) {
                record = new SendingRecord.ItemsAlreadySent();
            } else {
                throw new IllegalStateException("Could not record SENDING submission for hearing " + key.hearingId() + " case "
                        + key.caseId() + " defendant " + key.defendantId() + " (integrity violation other than the unique keys)");
            }
        }
        return record;
    }

    /** Unsendable: the request could not be built (FR-013). There is no request payload; {@code detail} carries no PII. */
    public void recordMappingFailed(final SubmissionKey key, final MappingFailureReason reason, final String detail) {
        final HearingResultSubmissionEntity entity = newRow(key, SubmissionStatus.MAPPING_FAILED);
        entity.setErrorDetail(reason + ": " + detail);
        insertIfAbsent(entity);
    }

    /** None of the defendant's results is a code GOB accepts (FR-009/FR-013). */
    public void recordSkippedNoResultCode(final SubmissionKey key, final List<String> droppedCodes) {
        final HearingResultSubmissionEntity entity = newRow(key, SubmissionStatus.SKIPPED_NO_RESULT_CODE);
        entity.setErrorDetail("no GOB-recognised result code; dropped " + droppedCodes);
        insertIfAbsent(entity);
    }

    /**
     * Nothing left to send (feature 002 FR-018, FR-020): every offence already sent, or the rest adjourned.
     * {@code detail} carries counts only. Returns the new row's id, or empty for a repeated delivery.
     */
    public Optional<UUID> recordSkippedNothingNew(final SubmissionKey key, final String detail) {
        final HearingResultSubmissionEntity entity = newRow(key, SubmissionStatus.SKIPPED_NOTHING_NEW);
        entity.setErrorDetail(detail);
        return insertIfAbsent(entity);
    }

    /**
     * Nothing was sent to GOB because something CP depends on failed before the call (e.g. reference data
     * unavailable). The outcome is known, so the row is FAILED with the reason and no request payload.
     */
    public void recordNotSent(final SubmissionKey key, final String detail) {
        final HearingResultSubmissionEntity entity = newRow(key, SubmissionStatus.FAILED);
        entity.setErrorDetail("NOT_SENT – " + detail);
        insertIfAbsent(entity);
    }

    /**
     * The submissions GOB accepted for a case and defendant, most recent first (feature 002 research R16: the
     * GOB data reused for a reshare or a share with nothing new).
     */
    public List<SucceededSubmission> findSucceeded(final UUID caseId, final UUID defendantId) {
        return repository.findByCaseIdAndDefendantIdAndStatusOrderByCreatedAtDesc(caseId, defendantId, SubmissionStatus.SUCCEEDED).stream()
                .map(e -> new SucceededSubmission(e.getId(), e.getRequestPayload(), e.getResponsePayload()))
                .toList();
    }

    /** The outcome of a hand-over of GOB data to hearing-nows (feature 002 FR-003); {@code detail} carries no PII. */
    @Transactional
    public void recordHandover(final UUID id, final NowsHandoverStatus status, final String detail) {
        final HearingResultSubmissionEntity entity = repository.findById(id).orElseThrow();
        entity.setNowsHandoverStatus(status);
        entity.setNowsHandoverDetail(detail);
        entity.setNowsHandoverAt(Instant.now(clock));
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

    private static HearingResultSubmissionEntity newRow(final SubmissionKey key, final SubmissionStatus status) {
        final HearingResultSubmissionEntity entity = new HearingResultSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setCorrelationId(key.correlationId());
        entity.setHearingId(key.hearingId());
        entity.setCaseId(key.caseId());
        entity.setDefendantId(key.defendantId());
        // a caseUrn over 36 characters is never truncated (Principle VI): the row keeps an empty value instead (also for null)
        entity.setCaseUrn(key.caseUrn() != null && key.caseUrn().length() <= CASE_URN_MAX ? key.caseUrn() : "");
        entity.setSharedTime(key.sharedTime());
        entity.setStatus(status);
        return entity;
    }

    private static GobSentOffenceEntity sentItemRow(final SubmissionKey key, final UUID submissionId, final SentItem item) {
        final GobSentOffenceEntity row = new GobSentOffenceEntity();
        row.setId(UUID.randomUUID());
        row.setCaseId(key.caseId());
        row.setDefendantId(key.defendantId());
        row.setOffenceId(item.offenceId());
        row.setJudicialResultId(item.judicialResultId());
        row.setSubmissionId(submissionId);
        row.setCorrelationId(key.correlationId());
        return row;
    }

    private boolean anyAlreadySent(final UUID caseId, final List<SentItem> items) {
        final List<UUID> offenceIds = items.stream().map(SentItem::offenceId).filter(Objects::nonNull).toList();
        final List<UUID> resultIds = items.stream().map(SentItem::judicialResultId).filter(Objects::nonNull).toList();
        return !offenceIds.isEmpty() && !sentOffenceRepository.findByCaseIdAndOffenceIdIn(caseId, offenceIds).isEmpty()
                || !resultIds.isEmpty() && !sentOffenceRepository.findByCaseIdAndJudicialResultIdIn(caseId, resultIds).isEmpty();
    }

    /**
     * Inserts the row, or returns empty when a row for the same share/case/defendant already exists (the
     * unique key). Any other integrity violation (e.g. a NOT NULL column) is a defect, not a duplicate. It is
     * rethrown without the database message, which can contain the payload (PII).
     */
    private Optional<UUID> insertIfAbsent(final HearingResultSubmissionEntity entity) {
        Optional<UUID> id;
        try {
            id = Optional.of(repository.saveAndFlush(entity).getId());
        } catch (DataIntegrityViolationException e) {
            if (findExisting(entity.getCorrelationId(), entity.getCaseId(), entity.getDefendantId()).isEmpty()) {
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
