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

/** Feature 003 (research R10): each stage is queued with the notification id only as job data. */
class NotificationTasksTest {

    private static final UUID ID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private final ExecutionService executionService = mock(ExecutionService.class);
    private final NotificationTasks tasks = new NotificationTasks(executionService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void process_should_queue_stage_1_with_the_notification_id_only() {
        tasks.queueProcess(ID);

        final ExecutionInfo queued = queued();
        assertThat(queued.getAssignedTaskName()).isEqualTo(NotificationTasks.PROCESS_ENFORCEMENT_NOTIFICATION);
        assertThat(queued.getJobData().keySet()).containsExactly("notificationId");
        assertThat(NotificationTasks.notificationIdOf(queued.getJobData())).isEqualTo(ID);
        assertThat(queued.getAssignedTaskStartTime().toInstant()).isEqualTo(NOW);
        assertThat(queued.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
    }

    @Test
    void publish_should_queue_stage_2_on_its_own() {
        tasks.queuePublish(ID);

        final ExecutionInfo queued = queued();
        assertThat(queued.getAssignedTaskName()).isEqualTo(NotificationTasks.PUBLISH_GOB_DATA);
        assertThat(NotificationTasks.notificationIdOf(queued.getJobData())).isEqualTo(ID);
    }

    private ExecutionInfo queued() {
        final ArgumentCaptor<ExecutionInfo> captor = ArgumentCaptor.forClass(ExecutionInfo.class);
        verify(executionService).executeWith(captor.capture());
        return captor.getValue();
    }
}
