package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.GovUkNotifyEmailSender;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.service.ResultNotificationTasks.DISPATCH_RESULT_NOTIFICATION_EMAIL;
import static uk.gov.hmcts.cp.service.ResultNotificationTasks.resultNotificationIdOf;

/**
 * Third stage of feature 005's own task chain: sends the {@code send-email-notification} command once the PDF is
 * ready ({@code DOC_READY}); the row's own outcome (SENT/EMAIL_FAILED) is recorded later by
 * {@link ResultNotificationReplyListener} from the gateway's own result event, not by this task.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(DISPATCH_RESULT_NOTIFICATION_EMAIL)
public class DispatchResultNotificationEmailTask implements ExecutableTask {

    private final ResultNotificationStore store;
    private final NotificationStore triggerStore;
    private final GovUkNotifyEmailSender emailSender;
    private final ResultNotificationRetries retries;

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID id = resultNotificationIdOf(executionInfo.getJobData());
        ExecutionInfo next;
        try {
            next = dispatch(executionInfo, id);
        } catch (RuntimeException e) {
            log.error("Enforcement result notification {} email dispatch failed ({})", id, e.getClass().getSimpleName());
            next = retries.retryOrFail(executionInfo, id, e.getClass().getSimpleName(), this::markEmailFailed);
        }
        return next;
    }

    // FR-016: EMAIL_FAILED (from a dispatch failure, as distinct from a later gateway-reported notification-failed)
    // is also a notification outcome that must be logged with caseUrn, Division Code, team email, shortCodes and the
    // sharing user's identifier
    private void markEmailFailed(final UUID id, final String error) {
        store.markEmailFailed(id, error);
        store.find(id).ifPresent(row -> log.info("Enforcement result notification {} EMAIL_FAILED: caseUrn={}, divisionCode={}, "
                + "area={}, teamEmail={}, resultShortCodes={}, sharingUser={}, error={}, at={}", id, row.getCaseUrn(),
                row.getDivisionCode(), row.getArea(), row.getTeamEmail(), row.getResultShortCodes(), callerOf(row), error, Instant.now()));
    }

    private String callerOf(final EnforcementResultNotificationEntity row) {
        return triggerStore.find(row.getTriggerNotificationId())
                .map(uk.gov.hmcts.cp.entity.EnforcementNotificationEntity::getCaller).orElse(null);
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    private ExecutionInfo dispatch(final ExecutionInfo executionInfo, final UUID id) {
        final Optional<EnforcementResultNotificationEntity> row = store.find(id);
        final ExecutionInfo next;
        if (row.isEmpty() || row.get().getStatus() != ResultNotificationStatus.DOC_READY) {
            log.info("Enforcement result notification {}: nothing to dispatch ({})", id,
                    row.map(EnforcementResultNotificationEntity::getStatus).orElse(null));
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            emailSender.sendEmail(id, row.get().getNotificationType(), row.get().getTeamEmail(), row.get().getDestinationFileUri());
            store.markEmailRequested(id);
            next = ResultNotificationRetries.completed(executionInfo);
        }
        return next;
    }
}
