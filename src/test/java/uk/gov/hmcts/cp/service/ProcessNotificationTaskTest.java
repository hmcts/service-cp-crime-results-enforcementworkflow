package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.client.ResultsUnavailableException;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Instant;
import java.time.LocalDate;
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

/** Feature 003 (US1, FR-006 to FR-010, FR-015, research R10): stage 1 of a notification. */
@ExtendWith(OutputCaptureExtension.class)
class ProcessNotificationTaskTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");

    private final NotificationStore store = mock(NotificationStore.class);
    private final HearingResultsSource source = mock(HearingResultsSource.class);
    private final HearingResultedProcessor processor = mock(HearingResultedProcessor.class);
    private final NotificationRetries retries = new NotificationRetries(new NotificationProperties(), store);
    private final ProcessNotificationTask task = new ProcessNotificationTask(store, source, processor, retries);

    @Test
    void ready_gob_data_should_continue_with_stage_2() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        when(store.find(ID)).thenAnswer(sequence(NotificationStatus.RECEIVED, NotificationStatus.SUBMITTED));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(event));

        final ExecutionInfo next = task.execute(info(3));

        verify(store).markProcessing(ID);
        verify(processor).process(event, ID);
        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.getAssignedTaskName()).isEqualTo(NotificationTasks.PUBLISH_GOB_DATA);
        assertThat(next.isShouldRetry()).isFalse();
    }

    @Test
    void no_gob_data_should_end_in_the_outcome_of_its_submission() {
        when(store.find(ID)).thenAnswer(sequence(NotificationStatus.RECEIVED, NotificationStatus.PROCESSING));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-enforcement.json")));
        when(store.submissionsOf(ID)).thenReturn(List.of(row(SubmissionStatus.FAILED)));

        final ExecutionInfo next = task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.GOB_FAILED, "FAILED");
        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void results_not_ready_should_be_retried_while_attempts_remain() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.RECEIVED)));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.empty());

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.isShouldRetry()).isTrue();
        verify(processor, never()).process(any(), any());
    }

    @Test
    void results_unavailable_after_the_last_retry_should_be_stored_with_a_replay() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.RECEIVED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenThrow(new ResultsUnavailableException("HTTP 503", null));

        final ExecutionInfo next = task.execute(info(0));

        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "RESULTS_UNAVAILABLE", Instant.parse("2026-10-20T10:15:00Z"));
        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void unexpected_failure_should_be_stored_with_the_exception_class_only(final CapturedOutput output) {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.RECEIVED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-enforcement.json")));
        org.mockito.Mockito.doThrow(new IllegalStateException("Edward Harrison")).when(processor).process(any(), eq(ID));

        task.execute(info(0));

        verify(store).markStoredFailure(eq(ID), eq(FailedStage.PROCESS), eq("IllegalStateException"), any());
        assertThat(withoutTempDiagnostics(output.getAll())).doesNotContain("Edward", "Harrison");
    }

    @Test
    void replayed_publish_failure_should_go_straight_to_stage_2() {
        final EnforcementNotificationEntity stored = notification(NotificationStatus.STORED_FAILURE);
        stored.setFailedStage(FailedStage.PUBLISH);
        when(store.find(ID)).thenReturn(Optional.of(stored));

        assertThat(task.execute(info(3)).getAssignedTaskName()).isEqualTo(NotificationTasks.PUBLISH_GOB_DATA);
        verify(store, never()).markProcessing(any());
        org.mockito.Mockito.verifyNoInteractions(source, processor);
    }

    // research R22: the notification's reshare flag decides how the results are processed (a reshare is never sent)
    @Test
    void reshare_notification_should_read_the_results_as_a_reshare() {
        final EnforcementNotificationEntity reshare = notification(NotificationStatus.RECEIVED);
        reshare.setReshare(true);
        when(store.find(ID)).thenReturn(Optional.of(reshare));
        when(source.sharedResults(HEARING_ID, DAY, true)).thenReturn(Optional.empty());
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));

        task.execute(info(3));

        verify(source).sharedResults(HEARING_ID, DAY, true);
    }

    @Test
    void reshare_waiting_for_its_first_share_should_be_retried() {
        when(store.find(ID)).thenReturn(Optional.of(reshare(NotificationStatus.RECEIVED)));
        when(source.sharedResults(HEARING_ID, DAY, true)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-reshare.json")));
        org.mockito.Mockito.doThrow(new FirstShareInProgressException(ID, HEARING_ID)).when(processor).process(any(), eq(ID));

        final ExecutionInfo next = task.execute(info(3));

        assertThat(next.isShouldRetry()).isTrue();
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
    }

    @Test
    void reshare_still_waiting_for_its_first_share_on_the_last_attempt_should_be_stored_with_a_replay() {
        when(store.find(ID)).thenReturn(Optional.of(reshare(NotificationStatus.RECEIVED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(source.sharedResults(HEARING_ID, DAY, true)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-reshare.json")));
        org.mockito.Mockito.doThrow(new FirstShareInProgressException(ID, HEARING_ID)).when(processor).process(any(), eq(ID));

        // 1 attempt remaining is the last run (NotificationRetries)
        final ExecutionInfo next = task.execute(info(1));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "FIRST_SHARE_IN_PROGRESS", Instant.parse("2026-10-20T10:15:00Z"));
    }

    // a failure reading or updating the notification itself is retried, then stored: never an exception escaping to
    // cp-task-manager (which would leave a job that never runs again)
    @Test
    void failure_reading_the_notification_should_be_retried_while_attempts_remain() {
        when(store.find(ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"));

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.isShouldRetry()).isTrue();
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(source, processor);
    }

    @Test
    void failure_reading_the_notification_on_the_last_attempt_should_be_stored() {
        when(store.find(ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .thenReturn(Optional.of(notification(NotificationStatus.RECEIVED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));

        final ExecutionInfo next = task.execute(info(1));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "DataAccessResourceFailureException",
                Instant.parse("2026-10-20T10:15:00Z"));
    }

    @Test
    void failure_marking_the_notification_processing_should_be_retried_then_stored() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.RECEIVED)));
        when(store.now()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .when(store).markProcessing(ID);

        assertThat(task.execute(info(2)).isShouldRetry()).isTrue();
        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "DataAccessResourceFailureException",
                Instant.parse("2026-10-20T10:15:00Z"));
        org.mockito.Mockito.verifyNoInteractions(source, processor);
    }

    @Test
    void final_notification_should_not_be_processed_again() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.PUBLISHED)));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).markProcessing(any());
    }

    @Test
    void unknown_notification_should_end_the_job() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).markProcessing(any());
    }

    @Test
    void notification_already_ready_should_continue_with_stage_2_without_processing_again() {
        when(store.find(ID)).thenReturn(Optional.of(notification(NotificationStatus.SKIPPED_NOTHING_NEW)));

        final ExecutionInfo next = task.execute(info(3));

        assertThat(next.getAssignedTaskName()).isEqualTo(NotificationTasks.PUBLISH_GOB_DATA);
        verify(processor, never()).process(any(), any());
    }

    @Test
    void stored_process_failure_should_be_processed_again_on_replay() {
        when(store.find(ID)).thenAnswer(sequence(NotificationStatus.STORED_FAILURE, NotificationStatus.SUBMITTED));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-enforcement.json")));

        task.execute(info(3));

        verify(store).markProcessing(ID);
        verify(processor).process(any(), eq(ID));
    }

    @Test
    void state_set_during_processing_should_be_kept() {
        when(store.find(ID)).thenAnswer(sequence(NotificationStatus.RECEIVED, NotificationStatus.PUBLISH_REJECTED));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-enforcement.json")));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).markFinal(any(), any(), any());
    }

    @Test
    void no_submission_row_should_end_as_not_enforcement_without_an_error() {
        when(store.find(ID)).thenAnswer(sequence(NotificationStatus.RECEIVED, NotificationStatus.PROCESSING));
        when(source.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(Fixtures.event("hearing-resulted-non-enforcement.json")));
        when(store.submissionsOf(ID)).thenReturn(List.of());

        task.execute(info(3));

        verify(store).markFinal(ID, NotificationStatus.SKIPPED_NOT_ENFORCEMENT, null);
    }

    @Test
    void outcome_should_follow_the_submission_rows() {
        assertThat(ProcessNotificationTask.outcomeOf(List.of())).isEqualTo(NotificationStatus.SKIPPED_NOT_ENFORCEMENT);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.SKIPPED_NO_RESULT_CODE))))
                .isEqualTo(NotificationStatus.SKIPPED_NO_RESULT_CODE);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.SKIPPED_NOTHING_NEW)))).isEqualTo(NotificationStatus.NO_GOB_DATA);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.MAPPING_FAILED)))).isEqualTo(NotificationStatus.GOB_FAILED);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.SUCCEEDED)))).isEqualTo(NotificationStatus.PUBLISH_REJECTED);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.SENDING)))).isEqualTo(NotificationStatus.GOB_FAILED);
        assertThat(ProcessNotificationTask.outcomeOf(List.of(row(SubmissionStatus.FAILED)))).isEqualTo(NotificationStatus.GOB_FAILED);
    }

    /** The notification as stored before processing, then after it. */
    private static org.mockito.stubbing.Answer<Optional<EnforcementNotificationEntity>> sequence(final NotificationStatus before,
                                                                                                final NotificationStatus after) {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        return invocation -> Optional.of(notification(calls.getAndIncrement() == 0 ? before : after));
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo().withJobData(NotificationTasks.jobData(ID))
                .withAssignedTaskName(NotificationTasks.PROCESS_ENFORCEMENT_NOTIFICATION).withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED).withRetryAttemptsRemaining(retriesRemaining).build();
    }

    private static EnforcementNotificationEntity notification(final NotificationStatus status) {
        final EnforcementNotificationEntity notification = new EnforcementNotificationEntity();
        notification.setId(ID);
        notification.setHearingId(HEARING_ID);
        notification.setHearingDate(DAY);
        notification.setStatus(status);
        return notification;
    }

    private static EnforcementNotificationEntity reshare(final NotificationStatus status) {
        final EnforcementNotificationEntity notification = notification(status);
        notification.setReshare(true);
        return notification;
    }

    private static HearingResultSubmissionEntity row(final SubmissionStatus status) {
        final HearingResultSubmissionEntity row = new HearingResultSubmissionEntity();
        row.setStatus(status);
        return row;
    }
}
