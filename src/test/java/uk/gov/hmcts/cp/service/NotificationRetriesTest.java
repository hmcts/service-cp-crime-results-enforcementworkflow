package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 003 (FR-015, research R10): retries with back-off, then a stored failure with the next automatic replay. */
class NotificationRetriesTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    private final NotificationProperties properties = new NotificationProperties();
    private final NotificationStore store = mock(NotificationStore.class);
    private final NotificationRetries retries = new NotificationRetries(properties, store);

    @Test
    void retry_durations_should_be_given_in_seconds_with_a_trailing_entry_for_the_last_run() {
        // cp-task-manager gives a job one attempt per duration: 4 runs = the first attempt + 3 retries (FR-015)
        assertThat(retries.retryDurationsInSecs()).contains(List.of(10L, 60L, 300L, 300L));
    }

    @Test
    void attempts_remaining_should_be_retried_until_the_last_run() {
        assertThat(retries.retryOrStore(info(2), ID, FailedStage.PROCESS, "X").isShouldRetry()).isTrue();
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
    }

    @Test
    void last_run_should_store_the_failure_instead_of_leaving_an_orphan_job() {
        when(store.find(ID)).thenReturn(Optional.of(notification(0)));
        when(store.now()).thenReturn(NOW);

        final ExecutionInfo next = retries.retryOrStore(info(1), ID, FailedStage.PROCESS, "X");

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "X", NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void unknown_remaining_attempts_should_store_the_failure() {
        when(store.find(ID)).thenReturn(Optional.of(notification(0)));
        when(store.now()).thenReturn(NOW);

        final ExecutionInfo next = retries.retryOrStore(info(null), ID, FailedStage.PROCESS, "X");

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markStoredFailure(ID, FailedStage.PROCESS, "X", NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void replay_schedule_should_follow_the_number_of_replays_and_end_with_none() {
        when(store.now()).thenReturn(NOW);
        properties.setReplaySchedule(new ArrayList<>(List.of(Duration.ofMinutes(15), Duration.ofHours(1))));

        assertThat(retries.nextReplayAt(notification(1))).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(retries.nextReplayAt(notification(2))).isNull();
    }

    @Test
    void schedule_used_up_should_store_the_failure_without_a_replay() {
        properties.setReplaySchedule(new ArrayList<>(List.of(Duration.ofMinutes(15))));
        when(store.find(ID)).thenReturn(Optional.of(notification(1)));

        retries.retryOrStore(info(0), ID, FailedStage.PUBLISH, "Y");

        verify(store).markStoredFailure(org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.eq(FailedStage.PUBLISH),
                org.mockito.ArgumentMatchers.eq("Y"), isNull());
    }

    @Test
    void notification_gone_should_end_the_job_without_storing() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(retries.retryOrStore(info(0), ID, FailedStage.PROCESS, "Z").getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
    }

    // the job ends even when the failure cannot be recorded (database down): no exception escapes to cp-task-manager
    @Test
    void failure_to_find_the_notification_when_storing_should_still_end_the_job() {
        when(store.find(ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"));

        final ExecutionInfo next = retries.retryOrStore(info(1), ID, FailedStage.PROCESS, "X");

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store, never()).markStoredFailure(any(), any(), any(), any());
    }

    @Test
    void failure_to_mark_the_stored_failure_should_still_end_the_job() {
        when(store.find(ID)).thenReturn(Optional.of(notification(0)));
        when(store.now()).thenReturn(NOW);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .when(store).markStoredFailure(any(), any(), any(), any());

        final ExecutionInfo next = retries.retryOrStore(info(1), ID, FailedStage.PUBLISH, "Y");

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(store).markStoredFailure(ID, FailedStage.PUBLISH, "Y", NOW.plus(Duration.ofMinutes(15)));
    }

    private static ExecutionInfo info(final Integer remaining) {
        return ExecutionInfo.executionInfo().withJobData(NotificationTasks.jobData(ID)).withAssignedTaskName("T")
                .withAssignedTaskStartTime(ZonedDateTime.now()).withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(remaining).build();
    }

    private static EnforcementNotificationEntity notification(final int replays) {
        final EnforcementNotificationEntity notification = new EnforcementNotificationEntity();
        notification.setId(ID);
        notification.setReplayCount(replays);
        return notification;
    }
}
