package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.ResultsUnavailableException;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.service.NotificationTasks.PROCESS_ENFORCEMENT_NOTIFICATION;
import static uk.gov.hmcts.cp.service.NotificationTasks.PUBLISH_GOB_DATA;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

/**
 * Stage 1 of a notification (feature 003, US1, FR-006 to FR-010, research R7, R9, R10): read the share's results, then
 * run feature 001/002's processing keyed by the notification, which selects, sends each offence to GOB once through
 * the gateway and records the outcome. When GOB data is ready, the job continues with stage 2 ({@link PublishGobDataTask});
 * otherwise the notification ends in a final state derived from its submission rows. Results not ready or unavailable
 * are retried; GOB is never called again for the same notification (the submission key holds), so a retry or replay
 * is safe. Logs carry ids and states only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(PROCESS_ENFORCEMENT_NOTIFICATION)
public class ProcessNotificationTask implements ExecutableTask {

    private final NotificationStore store;
    private final HearingResultsSource resultsSource;
    private final HearingResultedProcessor processor;
    private final NotificationRetries retries;

    // a failure anywhere in the stage (also reading or updating the notification) is retried, then stored (FR-015): an
    // exception escaping to cp-task-manager on the last attempt would leave a job that never runs again
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID id = NotificationTasks.notificationIdOf(executionInfo.getJobData());
        ExecutionInfo next;
        try {
            next = executeStage(executionInfo, id);
        } catch (RuntimeException e) {
            log.error("Notification {}: stage 1 failed ({})", id, e.getClass().getSimpleName());
            next = retries.retryOrStore(executionInfo, id, FailedStage.PROCESS, e.getClass().getSimpleName());
        }
        return next;
    }

    private ExecutionInfo executeStage(final ExecutionInfo executionInfo, final UUID id) {
        final Optional<EnforcementNotificationEntity> notification = store.find(id);
        final ExecutionInfo next;
        if (notification.isEmpty() || notification.get().getStatus().isFinal() && notification.get().getStatus() != NotificationStatus.STORED_FAILURE) {
            log.info("Notification {}: nothing to process ({})", id, notification.map(EnforcementNotificationEntity::getStatus).orElse(null));
            next = NotificationRetries.completed(executionInfo);
        } else if (notification.get().getStatus().isReadyToPublish() || isStoredPublishFailure(notification.get())) {
            // stage 1 is done: a replay of a stored publish failure goes straight to stage 2
            next = toPublish(executionInfo);
        } else {
            next = process(executionInfo, notification.get());
        }
        return next;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    // cp-task-manager requires a task to handle every failure itself (an escaping exception makes the job loop): any
    // failure is retried, then stored for a replay (FR-015)
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private ExecutionInfo process(final ExecutionInfo executionInfo, final EnforcementNotificationEntity notification) {
        final UUID id = notification.getId();
        // TEMP DIAGNOSTICS: stage 1 input
        TempPayloadLog.info("Stage 1 PROCESS for notification {}: hearingId={}, hearingDate={}, isReshare={}, status={}, retriesRemaining={}",
                id, notification.getHearingId(), notification.getHearingDate(), notification.isReshare(), notification.getStatus(),
                executionInfo.getRetryAttemptsRemaining());
        store.markProcessing(id);
        ExecutionInfo next;
        try {
            final Optional<HearingResultedEvent> results = resultsSource.sharedResults(notification.getHearingId(),
                    notification.getHearingDate(), notification.isReshare());
            if (results.isEmpty()) {
                log.info("Notification {}: results for hearing {} on {} not available yet", id, notification.getHearingId(),
                        notification.getHearingDate());
                next = retries.retryOrStore(executionInfo, id, FailedStage.PROCESS, "RESULTS_NOT_READY");
            } else {
                processor.process(results.get(), id);
                next = afterProcessing(executionInfo, id);
            }
        } catch (ResultsUnavailableException e) {
            next = retries.retryOrStore(executionInfo, id, FailedStage.PROCESS, "RESULTS_UNAVAILABLE");
        } catch (FirstShareInProgressException e) {
            log.info("Notification {}: reshare waits for the first share of hearing {} to reach GOB", id, notification.getHearingId());
            next = retries.retryOrStore(executionInfo, id, FailedStage.PROCESS, "FIRST_SHARE_IN_PROGRESS");
        } catch (RuntimeException e) {
            // never the message: it can quote payload data
            log.error("Notification {}: processing failed ({})", id, e.getClass().getSimpleName());
            // TEMP DIAGNOSTICS: message, root cause and stack trace
            TempPayloadLog.error("Stage 1 PROCESS for notification {} failed: {} - {}", id, e.getClass().getSimpleName(), e.getMessage(), e);
            next = retries.retryOrStore(executionInfo, id, FailedStage.PROCESS, e.getClass().getSimpleName());
        }
        return next;
    }

    private ExecutionInfo afterProcessing(final ExecutionInfo executionInfo, final UUID id) {
        final NotificationStatus status = store.find(id).orElseThrow().getStatus();
        final ExecutionInfo next;
        if (status.isReadyToPublish()) {
            next = toPublish(executionInfo);
        } else if (status == NotificationStatus.PROCESSING) {
            final List<HearingResultSubmissionEntity> rows = store.submissionsOf(id);
            final NotificationStatus outcome = outcomeOf(rows);
            store.markFinal(id, outcome, rows.isEmpty() ? null : rows.getFirst().getStatus().name());
            // TEMP DIAGNOSTICS: the final state and the submission rows behind it
            TempPayloadLog.info("Stage 1 PROCESS for notification {} ended {}: submissions={}", id, outcome,
                    rows.stream().map(row -> row.getId() + ":" + row.getStatus() + ":" + row.getErrorDetail()).toList());
            log.info("Notification {}: {}", id, outcome);
            next = NotificationRetries.completed(executionInfo);
        } else {
            next = NotificationRetries.completed(executionInfo);
        }
        return next;
    }

    private static boolean isStoredPublishFailure(final EnforcementNotificationEntity notification) {
        return notification.getStatus() == NotificationStatus.STORED_FAILURE && notification.getFailedStage() == FailedStage.PUBLISH;
    }

    /** The final state when no GOB data is ready to publish (data-model.md). */
    /* default */ static NotificationStatus outcomeOf(final List<HearingResultSubmissionEntity> rows) {
        final NotificationStatus outcome;
        if (rows.isEmpty()) {
            outcome = NotificationStatus.SKIPPED_NOT_ENFORCEMENT;
        } else {
            outcome = switch (rows.getFirst().getStatus()) {
                case SKIPPED_NO_RESULT_CODE -> NotificationStatus.SKIPPED_NO_RESULT_CODE;
                case SKIPPED_NOTHING_NEW -> NotificationStatus.NO_GOB_DATA;
                case SUCCEEDED -> NotificationStatus.PUBLISH_REJECTED; // GOB accepted, but its reply could not be read
                case SENDING, FAILED, MAPPING_FAILED -> NotificationStatus.GOB_FAILED;
            };
        }
        return outcome;
    }

    private static ExecutionInfo toPublish(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo)
                .withAssignedTaskName(PUBLISH_GOB_DATA)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(false)
                .build();
    }
}
