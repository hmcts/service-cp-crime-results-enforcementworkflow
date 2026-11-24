package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.repository.EnforcementResultNotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * State of {@code enforcement_result_notification} rows (feature 005, data-model.md). Every write commits on its own
 * ({@code REQUIRES_NEW}), mirroring {@link NotificationStore}: a recorded state survives a later failure of the same
 * task. Errors are codes only, never payload data (constitution IV).
 */
@Slf4j
@Component
public class ResultNotificationStore {

    private static final int LAST_ERROR_MAX = 255;

    private final EnforcementResultNotificationRepository repository;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public ResultNotificationStore(final EnforcementResultNotificationRepository repository,
                                   final PlatformTransactionManager transactionManager, final Clock clock) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public Optional<EnforcementResultNotificationEntity> find(final UUID id) {
        return requiresNew.execute(status -> repository.findById(id));
    }

    /** The Previous Share Snapshot (research.md R3): the most recent {@code SENT} row for the same hearing+reference. */
    public Optional<EnforcementResultNotificationEntity> previousShareSnapshot(final UUID hearingId, final String relatedReferenceNumber) {
        return requiresNew.execute(status -> repository.findPreviousShareSnapshot(hearingId, relatedReferenceNumber));
    }

    /**
     * Idempotently creates one {@code REQUESTED} row per unique {@code (triggerNotificationId, relatedReferenceNumber)}
     * (FR-015, research.md R10). Empty when this exact pair was already stored - a duplicate/retried fan-out of the
     * same trigger call, not a new row.
     */
    public Optional<EnforcementResultNotificationEntity> createRequested(final UUID triggerNotificationId, final UUID hearingId,
                                                                         final LocalDate hearingDate, final String relatedReferenceNumber,
                                                                         final NotificationType notificationType, final String caseUrn,
                                                                         final String divisionCode, final Area area) {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setTriggerNotificationId(triggerNotificationId);
        entity.setHearingId(hearingId);
        entity.setHearingDate(hearingDate);
        entity.setRelatedReferenceNumber(relatedReferenceNumber);
        entity.setNotificationType(notificationType);
        entity.setCaseUrn(caseUrn);
        // division_code/area are derived locally (never looked up) and are known as soon as relatedReferenceNumber
        // is, so both NOT NULL columns are populated at creation time, not left for MatchResultNotificationTask
        entity.setDivisionCode(divisionCode);
        entity.setArea(area);
        entity.setStatus(ResultNotificationStatus.REQUESTED);
        Optional<EnforcementResultNotificationEntity> created;
        try {
            created = requiresNew.execute(status -> Optional.of(repository.saveAndFlush(entity)));
        } catch (DataIntegrityViolationException e) {
            // never the reference number itself: it is this row's GOB account number (constitution IV)
            log.info("Enforcement result notification for trigger {} already exists; no new row", triggerNotificationId);
            created = Optional.empty();
        }
        return created;
    }

    public void markSuppressed(final UUID id) {
        update(id, n -> n.setStatus(ResultNotificationStatus.SUPPRESSED));
    }

    public void markInboxNotFound(final UUID id, final String divisionCode, final Area area) {
        update(id, n -> {
            n.setStatus(ResultNotificationStatus.INBOX_NOT_FOUND);
            n.setDivisionCode(divisionCode);
            n.setArea(area);
        });
    }

    public void markDocRequested(final UUID id, final String divisionCode, final Area area, final String teamEmail,
                                 final String resultShortCodesJson, final String payloadFileUri, final String destinationFileUri) {
        update(id, n -> {
            n.setStatus(ResultNotificationStatus.DOC_REQUESTED);
            n.setDivisionCode(divisionCode);
            n.setArea(area);
            n.setTeamEmail(teamEmail);
            n.setResultShortCodes(resultShortCodesJson);
            n.setPayloadFileUri(payloadFileUri);
            n.setDestinationFileUri(destinationFileUri);
        });
    }

    public void markDocReady(final UUID id) {
        update(id, n -> n.setStatus(ResultNotificationStatus.DOC_READY));
    }

    public void markDocFailed(final UUID id, final String error) {
        update(id, n -> {
            n.setStatus(ResultNotificationStatus.DOC_FAILED);
            n.setLastError(truncated(error));
        });
    }

    public void markEmailRequested(final UUID id) {
        update(id, n -> n.setStatus(ResultNotificationStatus.EMAIL_REQUESTED));
    }

    public void markSent(final UUID id) {
        update(id, n -> n.setStatus(ResultNotificationStatus.SENT));
    }

    public void markEmailFailed(final UUID id, final String error) {
        update(id, n -> {
            n.setStatus(ResultNotificationStatus.EMAIL_FAILED);
            n.setLastError(truncated(error));
        });
    }

    /** FR-018: only after {@code notification-sent} confirms delivery - never before. */
    public void markPurged(final UUID id) {
        update(id, n -> n.setPurgedAt(Instant.now(clock)));
    }

    private void update(final UUID id, final Consumer<EnforcementResultNotificationEntity> change) {
        requiresNew.executeWithoutResult(status -> {
            final EnforcementResultNotificationEntity entity = repository.findById(id).orElseThrow();
            change.accept(entity);
            repository.saveAndFlush(entity);
        });
    }

    private static String truncated(final String error) {
        return error == null || error.length() <= LAST_ERROR_MAX ? error : error.substring(0, LAST_ERROR_MAX);
    }
}
