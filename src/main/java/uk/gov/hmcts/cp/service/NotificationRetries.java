package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

/**
 * Retry and stored-failure handling shared by the notification tasks (feature 003, FR-015, research R10): a failing
 * stage is retried with back-off ({@code cp.notification.retry-durations}); after the last retry it is stored with the
 * next automatic replay from {@code cp.notification.replay-schedule}, and the job ends (cp-task-manager would otherwise
 * pick an exhausted job up again).
 *
 * <p>cp-task-manager runs a job only while its {@code retry_attempts_remaining} is above 0, and gives a new job as many
 * attempts as there are retry durations, decrementing on each retry. A retry scheduled from the run that sees 1 would
 * leave an orphan job that never runs. So the library is given the configured back-offs plus one trailing entry, and the
 * run that sees 1 attempt remaining is the last: first attempt + one retry per configured back-off (spec FR-015).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetries {

    private final NotificationProperties properties;
    private final NotificationStore store;

    public Optional<List<Long>> retryDurationsInSecs() {
        final List<Long> durations = new ArrayList<>(properties.getRetryDurations().stream().map(Duration::toSeconds).toList());
        durations.add(durations.getLast()); // the trailing entry: its attempt is the last run, never a retry (see above)
        return Optional.of(durations);
    }

    /** Retries the stage when attempts remain, otherwise stores the failure; {@code error} is a code only. */
    public ExecutionInfo retryOrStore(final ExecutionInfo executionInfo, final UUID notificationId, final FailedStage stage,
                                      final String error) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        final ExecutionInfo next;
        if (remaining != null && remaining > 1) {
            next = executionInfo().from(executionInfo).withExecutionStatus(INPROGRESS).withShouldRetry(true).build();
        } else {
            storeFailure(notificationId, stage, error);
            next = completed(executionInfo);
        }
        return next;
    }

    // the job ends either way: a failure to record it (database down) leaves the notification in its last state for
    // support (research OP-14) rather than a job that never runs again
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void storeFailure(final UUID notificationId, final FailedStage stage, final String error) {
        try {
            store.find(notificationId).ifPresent(n -> store.markStoredFailure(notificationId, stage, error, nextReplayAt(n)));
        } catch (RuntimeException e) {
            log.error("Notification {}: {} failure {} could not be stored ({})", notificationId, stage, error, e.getClass().getSimpleName());
        }
    }

    /** The next automatic replay, or null when the schedule is used up (the failure stays stored and visible). */
    public Instant nextReplayAt(final EnforcementNotificationEntity notification) {
        final List<Duration> schedule = properties.getReplaySchedule();
        return notification.getReplayCount() < schedule.size() ? store.now().plus(schedule.get(notification.getReplayCount())) : null;
    }

    public static ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo).withExecutionStatus(COMPLETED).withShouldRetry(false).build();
    }
}
