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
 * Task names and job data of feature 005's own, fully independent task chain (research.md R4, FR-010): job data
 * holds an id only, never results or payload data - a trigger notification id for {@link #queueResolve}, and this
 * feature's own {@code enforcement_result_notification} row id for every later stage.
 */
@Component
@RequiredArgsConstructor
public class ResultNotificationTasks {

    public static final String RESOLVE_RESULT_NOTIFICATION = "RESOLVE_RESULT_NOTIFICATION";
    public static final String MATCH_RESULT_NOTIFICATION = "MATCH_RESULT_NOTIFICATION";
    public static final String DISPATCH_RESULT_NOTIFICATION_EMAIL = "DISPATCH_RESULT_NOTIFICATION_EMAIL";
    private static final String TRIGGER_NOTIFICATION_ID = "triggerNotificationId";
    private static final String RESULT_NOTIFICATION_ID = "resultNotificationId";

    private final ExecutionService executionService;
    private final Clock clock;

    /** Queues resolution of a share/reshare trigger (feature 003's own notification id); runs in the caller's transaction. */
    public void queueResolve(final UUID triggerNotificationId) {
        queue(RESOLVE_RESULT_NOTIFICATION, TRIGGER_NOTIFICATION_ID, triggerNotificationId);
    }

    public void queueMatch(final UUID resultNotificationId) {
        queue(MATCH_RESULT_NOTIFICATION, RESULT_NOTIFICATION_ID, resultNotificationId);
    }

    public void queueDispatchEmail(final UUID resultNotificationId) {
        queue(DISPATCH_RESULT_NOTIFICATION_EMAIL, RESULT_NOTIFICATION_ID, resultNotificationId);
    }

    public static UUID triggerNotificationIdOf(final JsonObject jobData) {
        return UUID.fromString(jobData.getString(TRIGGER_NOTIFICATION_ID));
    }

    public static UUID resultNotificationIdOf(final JsonObject jobData) {
        return UUID.fromString(jobData.getString(RESULT_NOTIFICATION_ID));
    }

    private void queue(final String task, final String key, final UUID id) {
        executionService.executeWith(executionInfo()
                .withJobData(Json.createObjectBuilder().add(key, id.toString()).build())
                .withAssignedTaskName(task)
                .withAssignedTaskStartTime(ZonedDateTime.now(clock))
                .withExecutionStatus(STARTED)
                .build());
    }
}
