package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.GovUkNotifyEmailSender;
import uk.gov.hmcts.cp.client.GovUkNotifyUnavailableException;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 005: sends send-email-notification once the PDF is DOC_READY; the outcome is recorded later by the reply listener. */
class DispatchResultNotificationEmailTaskTest {

    private static final UUID ID = UUID.randomUUID();

    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final NotificationStore triggerStore = mock(NotificationStore.class);
    private final GovUkNotifyEmailSender emailSender = mock(GovUkNotifyEmailSender.class);
    private final ResultNotificationRetries retries = new ResultNotificationRetries(new ResultNotificationProperties());
    private final DispatchResultNotificationEmailTask task =
            new DispatchResultNotificationEmailTask(store, triggerStore, emailSender, retries);

    @Test
    void row_not_doc_ready_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.of(row(ResultNotificationStatus.DOC_REQUESTED)));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(emailSender, never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    void missing_row_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void doc_ready_should_send_the_email_and_mark_email_requested() {
        when(store.find(ID)).thenReturn(Optional.of(row(ResultNotificationStatus.DOC_READY)));

        task.execute(info(3));

        verify(emailSender).sendEmail(ID, NotificationType.FIRST_SHARE, "team@example.gov.uk", "destination-url");
        verify(store).markEmailRequested(ID);
    }

    @Test
    void send_failure_should_be_retried_then_marked_email_failed_on_the_last_attempt() {
        when(store.find(ID)).thenReturn(Optional.of(row(ResultNotificationStatus.DOC_READY)));
        doThrow(new GovUkNotifyUnavailableException("broker down", null)).when(emailSender)
                .sendEmail(any(), any(), any(), any());

        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markEmailFailed(ID, "GovUkNotifyUnavailableException");
    }

    private static EnforcementResultNotificationEntity row(final ResultNotificationStatus status) {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(ID);
        entity.setStatus(status);
        entity.setNotificationType(NotificationType.FIRST_SHARE);
        entity.setTeamEmail("team@example.gov.uk");
        entity.setDestinationFileUri("destination-url");
        return entity;
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jakarta.json.Json.createObjectBuilder().add("resultNotificationId", ID.toString()).build())
                .withAssignedTaskName(ResultNotificationTasks.DISPATCH_RESULT_NOTIFICATION_EMAIL)
                .withAssignedTaskStartTime(ZonedDateTime.now()).withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining).build();
    }
}
