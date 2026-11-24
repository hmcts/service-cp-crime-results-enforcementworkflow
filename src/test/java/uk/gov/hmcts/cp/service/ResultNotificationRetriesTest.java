package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Feature 005 (research.md R4, R11): a failing stage is retried with back-off, then its final failure is recorded
 * via whichever store method the caller supplies - no automatic replay schedule exists (manual recovery only).
 */
class ResultNotificationRetriesTest {

    private static final UUID ID = UUID.randomUUID();

    private final ResultNotificationProperties properties = new ResultNotificationProperties();
    private final ResultNotificationRetries retries = new ResultNotificationRetries(properties);

    @SuppressWarnings("unchecked")
    private final BiConsumer<UUID, String> storeFailure = mock(BiConsumer.class);

    @Test
    void retry_durations_should_be_given_in_seconds_with_a_trailing_entry_for_the_last_run() {
        assertThat(retries.retryDurationsInSecs()).contains(List.of(10L, 60L, 300L, 300L));
    }

    @Test
    void attempts_remaining_should_be_retried_without_recording_a_failure() {
        final ExecutionInfo next = retries.retryOrFail(info(2), ID, "X", storeFailure);

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(next.isShouldRetry()).isTrue();
        verifyNoInteractions(storeFailure);
    }

    @Test
    void last_run_should_record_the_failure_and_complete_the_job() {
        final ExecutionInfo next = retries.retryOrFail(info(1), ID, "X", storeFailure);

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
        verify(storeFailure).accept(ID, "X");
    }

    @Test
    void unknown_remaining_attempts_should_record_the_failure() {
        final ExecutionInfo next = retries.retryOrFail(info(null), ID, "X", storeFailure);

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(storeFailure).accept(ID, "X");
    }

    // the job ends even when the failure cannot be recorded (database down): no exception escapes to cp-task-manager
    @Test
    void failure_to_record_the_failure_should_still_end_the_job() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .when(storeFailure).accept(ID, "X");

        final ExecutionInfo next = retries.retryOrFail(info(1), ID, "X", storeFailure);

        assertThat(next.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(next.isShouldRetry()).isFalse();
    }

    private static ExecutionInfo info(final Integer remaining) {
        final jakarta.json.JsonObject jobData = jakarta.json.Json.createObjectBuilder()
                .add("resultNotificationId", ID.toString()).build();
        return ExecutionInfo.executionInfo().withJobData(jobData)
                .withAssignedTaskName("T").withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED).withRetryAttemptsRemaining(remaining).build();
    }
}
