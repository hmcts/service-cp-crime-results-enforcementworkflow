package uk.gov.hmcts.cp.service;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.UUID;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

/**
 * Task names and job data of a notification's stages (feature 003, research R10). Job data holds the notification id
 * only, never results or GOB data.
 */
@Component
@RequiredArgsConstructor
public class NotificationTasks {

    public static final String PROCESS_ENFORCEMENT_NOTIFICATION = "PROCESS_ENFORCEMENT_NOTIFICATION";
    public static final String PUBLISH_GOB_DATA = "PUBLISH_GOB_DATA";
    private static final String NOTIFICATION_ID = "notificationId";

    private final ExecutionService executionService;
    private final Clock clock;

    /** Queues stage 1 of a notification; runs in the caller's transaction, so a stored notification is always queued. */
    public void queueProcess(final UUID notificationId) {
        queue(PROCESS_ENFORCEMENT_NOTIFICATION, notificationId);
    }

    /** Queues stage 2 on its own, e.g. a replay of a failed publish. */
    public void queuePublish(final UUID notificationId) {
        queue(PUBLISH_GOB_DATA, notificationId);
    }

    public static JsonObject jobData(final UUID notificationId) {
        return Json.createObjectBuilder().add(NOTIFICATION_ID, notificationId.toString()).build();
    }

    public static UUID notificationIdOf(final JsonObject jobData) {
        return UUID.fromString(jobData.getString(NOTIFICATION_ID));
    }

    private void queue(final String task, final UUID notificationId) {
        executionService.executeWith(executionInfo()
                .withJobData(jobData(notificationId))
                .withAssignedTaskName(task)
                .withAssignedTaskStartTime(ZonedDateTime.now(clock))
                .withExecutionStatus(STARTED)
                .build());
    }
}
