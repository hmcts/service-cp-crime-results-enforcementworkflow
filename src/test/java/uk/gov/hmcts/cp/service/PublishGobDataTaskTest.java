package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jms.UncategorizedJmsException;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.messaging.GobDataEventPublisher;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/** Feature 003 (US1, FR-012, FR-015, research R12): stage 2, the GOB data event; GOB is never contacted. */
@ExtendWith(OutputCaptureExtension.class)
class PublishGobDataTaskTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final UUID MASTER_DEFENDANT_ID = UUID.randomUUID();
    private static final UUID OTHER_MASTER_DEFENDANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private final NotificationStore store = mock(NotificationStore.class);
    private final GobDataEventPublisher publisher = mock(GobDataEventPublisher.class);
    private final PublishGobDataTask task = new PublishGobDataTask(store, new NowsHandoverPayloadBuilder(), publisher,
            new NotificationRetries(new NotificationProperties(), store));

    @Test
    void ready_gob_data_should_be_published_for_the_selected_defendants_requests_only() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID),
                new EnforcementNotificationNowEntity(UUID.randomUUID(), ID, OTHER_MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("{\"nowsDataItems\":{\"accountBalance\":1}}")));
        final org.mockito.ArgumentCaptor<ObjectNode> payload = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        when(publisher.publish(payload.capture())).thenReturn(UUID.randomUUID());

        final ExecutionInfo next = task.execute(info(3));

        assertThat(payload.getValue().path("nows")).hasSize(1);
        assertThat(payload.getValue().path("nows").get(0).path("requestId").asString()).isEqualTo(REQUEST_ID.toString());
        assertThat(payload.getValue().path("accountNumber").asString()).isEqualTo("26000123A");
        verify(store).markFinal(ID, NotificationStatus.PUBLISHED, null);
        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void no_request_for_the_selected_defendant_should_reject_publishing() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, OTHER_MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("{\"nowsDataItems\":{}}")));

        task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.PUBLISH_REJECTED, "NO_NOW_FOR_SELECTED_DEFENDANT");
        verify(publisher, never()).publish(any());
    }

    @Test
    void unreadable_gob_reply_should_reject_publishing() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("\"not json\"")));

        task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.PUBLISH_REJECTED, "GOB_REPLY_UNREADABLE");
        verify(publisher, never()).publish(any());
    }

    @Test
    void missing_account_number_should_reject_publishing() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        final HearingResultSubmissionEntity row = submission("{\"nowsDataItems\":{}}");
        row.setRequestPayload("{\"defendantDetails\":{}}");
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(row));

        task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.PUBLISH_REJECTED, "NO_ACCOUNT_NUMBER");
        verify(publisher, never()).publish(any());
    }

    @Test
    void unknown_notification_should_publish_nothing() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(publisher, never()).publish(any());
    }

    @Test
    void stored_process_failure_should_not_be_published() {
        final EnforcementNotificationEntity stored = notification(NotificationStatus.STORED_FAILURE);
        stored.setFailedStage(FailedStage.PROCESS);
        when(store.find(ID)).thenReturn(Optional.of(stored));

        task.execute(info(3));

        verify(publisher, never()).publish(any());
        verify(store, never()).markFinal(any(), any(), any());
    }

    @Test
    void reused_gob_data_should_be_published_as_reused() {
        final EnforcementNotificationEntity reused = notification(NotificationStatus.SKIPPED_NOTHING_NEW);
        reused.setGobDataReused(true);
        when(store.find(ID)).thenReturn(Optional.of(reused));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("{\"nowsDataItems\":{}}")));
        final org.mockito.ArgumentCaptor<ObjectNode> payload = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        when(publisher.publish(payload.capture())).thenReturn(UUID.randomUUID());

        task.execute(info(3));

        assertThat(payload.getValue().path("gobDataReused").asBoolean()).isTrue();
        assertThat(payload.getValue().path("sharedTime").asString()).isEqualTo("2026-05-03T14:30:00Z");
    }

    @Test
    void broker_failure_should_be_retried_then_stored_for_a_replay() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("{\"nowsDataItems\":{}}")));
        when(publisher.publish(any())).thenThrow(new UncategorizedJmsException("broker down"));

        assertThat(task.execute(info(2)).isShouldRetry()).isTrue();
        task.execute(info(1));

        verify(store).markStoredFailure(eq(ID), eq(FailedStage.PUBLISH), eq("UncategorizedJmsException"), any());
    }

    @Test
    void stored_publish_failure_should_be_publishable_on_replay() {
        final EnforcementNotificationEntity stored = notification(NotificationStatus.STORED_FAILURE);
        stored.setFailedStage(FailedStage.PUBLISH);
        when(store.find(ID)).thenReturn(Optional.of(stored));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(submission("{\"nowsDataItems\":{}}")));
        when(publisher.publish(any())).thenReturn(UUID.randomUUID());

        task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.PUBLISHED, null);
    }

    @Test
    void notification_not_ready_should_publish_nothing() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.GOB_FAILED)));

        task.execute(info(3));

        verify(publisher, never()).publish(any());
        verify(store, never()).markFinal(any(), any(), any());
    }

    // GOB account data (the account number sent to GOB, GOB's balance) is never logged outside the TEMP diagnostics
    @Test
    void published_gob_account_data_should_never_be_logged(final CapturedOutput output) {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.nowsOf(ID)).thenReturn(List.of(new EnforcementNotificationNowEntity(REQUEST_ID, ID, MASTER_DEFENDANT_ID)));
        final HearingResultSubmissionEntity row = submission("{\"nowsDataItems\":{\"accountBalance\":98765.43}}");
        row.setRequestPayload("{\"defendantDetails\":{\"prosecutorDefendantId\":\"SENTINEL991\"}}");
        when(store.submission(SUBMISSION_ID)).thenReturn(Optional.of(row));
        final org.mockito.ArgumentCaptor<ObjectNode> payload = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        when(publisher.publish(payload.capture())).thenReturn(UUID.randomUUID());

        task.execute(info(3));

        assertThat(payload.getValue().path("accountNumber").asString()).isEqualTo("SENTINEL991");
        verify(store).markFinal(ID, NotificationStatus.PUBLISHED, null);
        assertThat(withoutTempDiagnostics(output.getAll())).doesNotContain("SENTINEL991", "98765.43", "98765");
    }

    // a failure reading or updating the notification itself is retried, then stored: never an exception escaping to
    // cp-task-manager (which would leave a job that never runs again)
    @Test
    void failure_reading_the_notification_should_be_retried_while_attempts_remain() {
        when(store.find(ID)).thenThrow(new DataAccessResourceFailureException("database down"));

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.isShouldRetry()).isTrue();
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void failure_reading_the_notification_on_the_last_attempt_should_be_stored() {
        when(store.find(ID)).thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));

        final ExecutionInfo next = task.execute(info(1));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store).markStoredFailure(ID, FailedStage.PUBLISH, "DataAccessResourceFailureException",
                Instant.parse("2026-10-20T10:15:00Z"));
    }

    @Test
    void failure_reading_the_now_requests_should_be_retried_then_stored() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SUBMITTED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(store.nowsOf(ID)).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThat(task.execute(info(2)).isShouldRetry()).isTrue();
        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markStoredFailure(ID, FailedStage.PUBLISH, "DataAccessResourceFailureException",
                Instant.parse("2026-10-20T10:15:00Z"));
        verify(publisher, never()).publish(any());
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo().withJobData(NotificationTasks.jobData(ID))
                .withAssignedTaskName(NotificationTasks.PUBLISH_GOB_DATA).withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED).withRetryAttemptsRemaining(retriesRemaining).build();
    }

    private static EnforcementNotificationEntity notification(final NotificationStatus status) {
        final EnforcementNotificationEntity notification = new EnforcementNotificationEntity();
        notification.setId(ID);
        notification.setHearingId(UUID.randomUUID());
        notification.setStatus(status);
        notification.setSubmissionId(SUBMISSION_ID);
        notification.setMasterDefendantId(MASTER_DEFENDANT_ID);
        notification.setGobDataReused(false);
        return notification;
    }

    private static HearingResultSubmissionEntity submission(final String reply) {
        final HearingResultSubmissionEntity row = new HearingResultSubmissionEntity();
        row.setId(SUBMISSION_ID);
        row.setRequestPayload("{\"defendantDetails\":{\"prosecutorDefendantId\":\"26000123A\"}}");
        row.setResponsePayload(reply);
        row.setSharedTime(Instant.parse("2026-05-03T14:30:00Z"));
        return row;
    }
}
