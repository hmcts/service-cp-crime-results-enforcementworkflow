package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.messaging.GobDataEventPublisher;
import uk.gov.hmcts.cp.service.NowsHandoverPayloadBuilder.NowRequest;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.service.NotificationTasks.PUBLISH_GOB_DATA;

/**
 * Stage 2 of a notification (feature 003, US1, FR-012, research R12): builds the GOB data event from the source
 * submission (GOB's {@code nowsDataItems} verbatim, the account number sent to GOB) and the notification's NOW requests
 * of the selected defendant, and publishes it. GOB is never contacted here, so a retry or replay is safe; hearing-nows
 * ignores a repeated request id (constitution V 1.2.1). Nothing publishable (no NOW request for the selected defendant,
 * no readable GOB data) ends {@link NotificationStatus#PUBLISH_REJECTED}. Logs carry ids only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(PUBLISH_GOB_DATA)
public class PublishGobDataTask implements ExecutableTask {

    private final NotificationStore store;
    private final NowsHandoverPayloadBuilder payloadBuilder;
    private final GobDataEventPublisher publisher;
    private final NotificationRetries retries;

    // a failure anywhere in the stage (also reading or updating the notification) is retried, then stored (FR-015): an
    // exception escaping to cp-task-manager on the last attempt would leave a job that never runs again
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID id = NotificationTasks.notificationIdOf(executionInfo.getJobData());
        ExecutionInfo next = NotificationRetries.completed(executionInfo);
        try {
            final Optional<EnforcementNotificationEntity> notification = store.find(id);
            if (notification.isEmpty() || !isPublishable(notification.get())) {
                log.info("Notification {}: nothing to publish ({})", id, notification.map(EnforcementNotificationEntity::getStatus).orElse(null));
            } else {
                next = publish(executionInfo, notification.get());
            }
        } catch (RuntimeException e) {
            log.error("Notification {}: stage 2 failed ({})", id, e.getClass().getSimpleName());
            next = retries.retryOrStore(executionInfo, id, FailedStage.PUBLISH, e.getClass().getSimpleName());
        }
        return next;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    // cp-task-manager requires a task to handle every failure itself (an escaping exception makes the job loop): a failed
    // publish is retried, then stored for a replay (FR-015)
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private ExecutionInfo publish(final ExecutionInfo executionInfo, final EnforcementNotificationEntity notification) {
        final UUID id = notification.getId();
        ExecutionInfo next = NotificationRetries.completed(executionInfo);
        final List<EnforcementNotificationNowEntity> notificationNows = store.nowsOf(id);
        final List<NowRequest> nows = notificationNows.stream()
                .filter(now -> now.getMasterDefendantId().equals(notification.getMasterDefendantId()))
                .map(now -> new NowRequest(now.getRequestId(), now.getMasterDefendantId()))
                .toList();
        final Optional<HearingResultSubmissionEntity> source = store.submission(notification.getSubmissionId());
        final Optional<JsonNode> nowsDataItems = source.flatMap(s -> NowsHandoverPayloadBuilder.nowsDataItemsOf(s.getResponsePayload()));
        final Optional<String> accountNumber = source.flatMap(s -> GobReplies.accountNumberOf(s.getRequestPayload()));
        // TEMP DIAGNOSTICS: stage 2 input
        TempPayloadLog.info("Stage 2 PUBLISH for notification {}: masterDefendantId={}, submissionId={}, reused={}, nows={}, "
                        + "allNotificationNows={}, accountNumber={}, nowsDataItems={}", id, notification.getMasterDefendantId(),
                notification.getSubmissionId(), notification.getGobDataReused(), nows,
                notificationNows.stream().map(now -> now.getRequestId() + ":" + now.getMasterDefendantId()).toList(),
                accountNumber.orElse(null), nowsDataItems.map(Object::toString).orElse(null));
        if (nows.isEmpty() || nowsDataItems.isEmpty() || accountNumber.isEmpty()) {
            final String reason = nows.isEmpty() ? "NO_NOW_FOR_SELECTED_DEFENDANT" : nowsDataItems.isEmpty() ? "GOB_REPLY_UNREADABLE" : "NO_ACCOUNT_NUMBER";
            store.markFinal(id, NotificationStatus.PUBLISH_REJECTED, reason);
            log.warn("Notification {}: GOB data not published ({})", id, reason);
            // TEMP DIAGNOSTICS: why, with the inputs above
            TempPayloadLog.warn("Stage 2 PUBLISH for notification {} rejected: {}", id, reason);
        } else {
            try {
                final UUID messageId = publisher.publish(payloadBuilder.build(notification.getHearingId(), nows, accountNumber.get(),
                        nowsDataItems.get(), Boolean.TRUE.equals(notification.getGobDataReused()), source.get().getSharedTime()));
                store.markFinal(id, NotificationStatus.PUBLISHED, null);
                log.info("Notification {}: GOB data published for {} NOW request(s) (message {}, reused {})", id, nows.size(),
                        messageId, notification.getGobDataReused());
            } catch (RuntimeException e) {
                log.warn("Notification {}: publishing GOB data failed ({})", id, e.getClass().getSimpleName());
                // TEMP DIAGNOSTICS: message and stack trace
                TempPayloadLog.error("Stage 2 PUBLISH for notification {} failed: {} - {}", id, e.getClass().getSimpleName(),
                        e.getMessage(), e);
                next = retries.retryOrStore(executionInfo, id, FailedStage.PUBLISH, e.getClass().getSimpleName());
            }
        }
        return next;
    }

    private static boolean isPublishable(final EnforcementNotificationEntity notification) {
        return notification.getStatus().isReadyToPublish()
                || notification.getStatus() == NotificationStatus.STORED_FAILURE && notification.getFailedStage() == FailedStage.PUBLISH;
    }
}
