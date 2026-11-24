package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Feature 005 (research.md R4, FR-010): job data holds an id only, never results or payload data. */
class ResultNotificationTasksTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final ExecutionService executionService = mock(ExecutionService.class);
    private final ResultNotificationTasks tasks = new ResultNotificationTasks(executionService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void resolve_should_queue_with_the_trigger_notification_id() {
        tasks.queueResolve(ID);

        final ExecutionInfo queued = queued();
        assertThat(queued.getAssignedTaskName()).isEqualTo(ResultNotificationTasks.RESOLVE_RESULT_NOTIFICATION);
        assertThat(queued.getJobData().keySet()).containsExactly("triggerNotificationId");
        assertThat(ResultNotificationTasks.triggerNotificationIdOf(queued.getJobData())).isEqualTo(ID);
        assertThat(queued.getAssignedTaskStartTime().toInstant()).isEqualTo(NOW);
        assertThat(queued.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
    }

    @Test
    void match_should_queue_with_the_result_notification_id() {
        tasks.queueMatch(ID);

        final ExecutionInfo queued = queued();
        assertThat(queued.getAssignedTaskName()).isEqualTo(ResultNotificationTasks.MATCH_RESULT_NOTIFICATION);
        assertThat(ResultNotificationTasks.resultNotificationIdOf(queued.getJobData())).isEqualTo(ID);
    }

    @Test
    void dispatch_email_should_queue_with_the_result_notification_id() {
        tasks.queueDispatchEmail(ID);

        final ExecutionInfo queued = queued();
        assertThat(queued.getAssignedTaskName()).isEqualTo(ResultNotificationTasks.DISPATCH_RESULT_NOTIFICATION_EMAIL);
        assertThat(ResultNotificationTasks.resultNotificationIdOf(queued.getJobData())).isEqualTo(ID);
    }

    private ExecutionInfo queued() {
        final ArgumentCaptor<ExecutionInfo> captor = ArgumentCaptor.forClass(ExecutionInfo.class);
        verify(executionService).executeWith(captor.capture());
        return captor.getValue();
    }
}
