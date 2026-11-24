package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R4, FR-010): resolves the hearing's current state (deliberately re-reading it, not
 * sharing state with the GOB pipeline) and idempotently creates one row per unique relatedReferenceNumber.
 */
class ResolveResultNotificationTaskTest {

    private static final UUID TRIGGER_ID = UUID.randomUUID();
    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");

    private final NotificationStore notificationStore = mock(NotificationStore.class);
    private final HearingResultsSource resultsSource = mock(HearingResultsSource.class);
    private final EnforcementCaseSelector caseSelector = mock(EnforcementCaseSelector.class);
    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final ResultNotificationTasks tasks = mock(ResultNotificationTasks.class);
    private final ResultNotificationProperties properties = new ResultNotificationProperties();
    private final ResultNotificationRetries retries = new ResultNotificationRetries(properties);
    private final ResolveResultNotificationTask task = new ResolveResultNotificationTask(notificationStore, resultsSource,
            caseSelector, store, tasks, retries, properties);

    @Test
    void unknown_trigger_should_end_the_job_without_creating_a_row() {
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).createRequested(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void results_not_ready_should_be_retried_while_attempts_remain() {
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(false)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.empty());

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.isShouldRetry()).isTrue();
    }

    @Test
    void results_still_not_ready_on_the_last_attempt_should_complete_without_a_row() {
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(false)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.empty());

        assertThat(task.execute(info(1)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store, never()).createRequested(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void selected_first_share_should_create_a_requested_row_and_queue_match() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(false)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(new SelectionResult.Selected(
                event.hearing().prosecutionCases().getFirst(), defendant));
        final EnforcementResultNotificationEntity created = new EnforcementResultNotificationEntity();
        created.setId(UUID.randomUUID());
        when(store.createRequested(eq(TRIGGER_ID), eq(HEARING_ID), eq(DAY), eq(defendant.prosecutionAuthorityReference()),
                eq(NotificationType.FIRST_SHARE), any(), any(), any())).thenReturn(Optional.of(created));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verify(tasks).queueMatch(created.getId());
    }

    @Test
    void selected_reshare_should_create_an_amend_reshare_row() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-reshare.json");
        final HearingResultedEvent.Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(true)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, true)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(new SelectionResult.Reshare(
                event.hearing().prosecutionCases().getFirst(), defendant));
        when(store.createRequested(any(), any(), any(), any(), eq(NotificationType.AMEND_RESHARE), any(), any(), any()))
                .thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        task.execute(info(3));

        verify(store).createRequested(eq(TRIGGER_ID), eq(HEARING_ID), eq(DAY), eq(defendant.prosecutionAuthorityReference()),
                eq(NotificationType.AMEND_RESHARE), any(), any(), any());
    }

    @Test
    void out_of_scope_hearing_should_not_create_a_row() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-non-enforcement.json");
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(false)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(new SelectionResult.Skipped(SkipReason.NO_ENFORCEMENT_CASE));

        task.execute(info(3));

        verify(store, never()).createRequested(any(), any(), any(), any(), any(), any(), any(), any());
        verify(tasks, never()).queueMatch(any());
    }

    // FR-015 / research.md R10: a duplicate fan-out of the same trigger call is not queued again
    @Test
    void duplicate_trigger_and_reference_should_not_queue_match_again() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();
        when(notificationStore.find(TRIGGER_ID)).thenReturn(Optional.of(trigger(false)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(new SelectionResult.Selected(
                event.hearing().prosecutionCases().getFirst(), defendant));
        when(store.createRequested(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        task.execute(info(3));

        verify(tasks, never()).queueMatch(any());
    }

    @Test
    void unexpected_failure_should_be_retried_while_attempts_remain() {
        when(notificationStore.find(TRIGGER_ID)).thenThrow(new RuntimeException("boom"));

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.isShouldRetry()).isTrue();
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jakarta.json.Json.createObjectBuilder().add("triggerNotificationId", TRIGGER_ID.toString()).build())
                .withAssignedTaskName(ResultNotificationTasks.RESOLVE_RESULT_NOTIFICATION).withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED).withRetryAttemptsRemaining(retriesRemaining).build();
    }

    private static EnforcementNotificationEntity trigger(final boolean reshare) {
        final EnforcementNotificationEntity entity = new EnforcementNotificationEntity();
        entity.setId(TRIGGER_ID);
        entity.setHearingId(HEARING_ID);
        entity.setHearingDate(DAY);
        entity.setReshare(reshare);
        return entity;
    }
}
