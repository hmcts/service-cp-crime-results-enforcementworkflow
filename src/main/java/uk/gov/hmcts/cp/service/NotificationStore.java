package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.repository.EnforcementNotificationNowRepository;
import uk.gov.hmcts.cp.repository.EnforcementNotificationRepository;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * State of enforcement notifications (feature 003, data-model.md). The tasks run inside cp-task-manager's transaction,
 * so every write here commits on its own (REQUIRES_NEW): a recorded state survives a later failure of the same task.
 * Errors are codes only, never payload data.
 */
@Slf4j
@Component
public class NotificationStore {

    private static final int LAST_ERROR_MAX = 255;

    private final EnforcementNotificationRepository repository;
    private final EnforcementNotificationNowRepository nowRepository;
    private final HearingResultSubmissionRepository submissionRepository;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public NotificationStore(final EnforcementNotificationRepository repository, final EnforcementNotificationNowRepository nowRepository,
                             final HearingResultSubmissionRepository submissionRepository,
                             final PlatformTransactionManager transactionManager, final Clock clock) {
        this.repository = repository;
        this.nowRepository = nowRepository;
        this.submissionRepository = submissionRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public Optional<EnforcementNotificationEntity> find(final UUID id) {
        return requiresNew.execute(status -> repository.findById(id));
    }

    public List<EnforcementNotificationNowEntity> nowsOf(final UUID id) {
        return requiresNew.execute(status -> nowRepository.findByNotificationId(id));
    }

    public List<HearingResultSubmissionEntity> submissionsOf(final UUID id) {
        return requiresNew.execute(status -> submissionRepository.findByCorrelationId(id));
    }

    public Optional<HearingResultSubmissionEntity> submission(final UUID submissionId) {
        return requiresNew.execute(status -> submissionRepository.findById(submissionId));
    }

    /** Stage 1 starts (or a replay of it): one more attempt. */
    public void markProcessing(final UUID id) {
        update(id, n -> {
            n.setStatus(NotificationStatus.PROCESSING);
            n.setAttempts(n.getAttempts() + 1);
        });
    }

    /** GOB data is ready for stage 2: a new submission ({@code reused=false}) or earlier GOB data reused (FR-009). */
    public void markReadyToPublish(final UUID id, final UUID sourceSubmissionId, final UUID masterDefendantId, final boolean reused) {
        update(id, n -> {
            n.setStatus(reused ? NotificationStatus.SKIPPED_NOTHING_NEW : NotificationStatus.SUBMITTED);
            n.setSubmissionId(sourceSubmissionId);
            n.setMasterDefendantId(masterDefendantId);
            n.setGobDataReused(reused);
            n.setFailedStage(null);
            n.setLastError(null);
            n.setAttempts(0);
        });
    }

    /** A final state; {@code error} is a code (e.g. a submission status or a reason), or null. */
    public void markFinal(final UUID id, final NotificationStatus status, final String error) {
        update(id, n -> {
            n.setStatus(status);
            n.setLastError(truncated(error));
            n.setFailedStage(null); // a replayed stored failure that ends normally keeps no stale stage
            n.setNextReplayAt(null);
        });
    }

    /** A stage failed after its retries (FR-015): stored, with the next automatic replay or none. */
    public void markStoredFailure(final UUID id, final FailedStage stage, final String error, final Instant nextReplayAt) {
        update(id, n -> {
            n.setStatus(NotificationStatus.STORED_FAILURE);
            n.setFailedStage(stage);
            n.setLastError(truncated(error));
            n.setNextReplayAt(nextReplayAt);
        });
    }

    /**
     * Whether a first-time share of the hearing is still on its way to GOB (received, processing, or stored for a replay
     * of stage 1), so a reshare must wait for its GOB data rather than end without it (feature 003 FR-009).
     */
    public boolean firstShareInProgress(final UUID hearingId, final UUID reshareId) {
        return repository.existsByHearingIdAndReshareFalseAndIdNotAndStatusIn(hearingId, reshareId,
                List.of(NotificationStatus.RECEIVED, NotificationStatus.PROCESSING, NotificationStatus.STORED_FAILURE));
    }

    public Instant now() {
        return Instant.now(clock);
    }

    private void update(final UUID id, final Consumer<EnforcementNotificationEntity> change) {
        requiresNew.executeWithoutResult(status -> {
            final EnforcementNotificationEntity entity = repository.findById(id).orElseThrow();
            change.accept(entity);
            repository.saveAndFlush(entity);
        });
    }

    private static String truncated(final String error) {
        return error == null || error.length() <= LAST_ERROR_MAX ? error : error.substring(0, LAST_ERROR_MAX);
    }
}
