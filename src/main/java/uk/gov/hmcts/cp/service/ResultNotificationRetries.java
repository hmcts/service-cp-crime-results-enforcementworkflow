package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

/**
 * Retry handling for feature 005's own task chain (research.md R4, R11): a failing stage is retried with back-off
 * ({@code cp.result-notification.retry-durations}); after the last retry the row is marked final
 * ({@code DOC_FAILED}/{@code EMAIL_FAILED}) and the job ends. Unlike {@link NotificationRetries}, there is no
 * automatic replay schedule: FR-017's "recoverable without resubmitting the hearing" is satisfied by manual
 * re-triggering of the failed step against the existing row id (Clarification 2026-11-24 - automatic retry is
 * explicitly out of scope, deferred to a separate story).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultNotificationRetries {

    private final ResultNotificationProperties properties;

    public Optional<List<Long>> retryDurationsInSecs() {
        final List<Long> durations = new ArrayList<>(properties.getRetryDurations().stream().map(Duration::toSeconds).toList());
        durations.add(durations.getLast()); // the trailing entry's attempt is the last run, never a retry
        return Optional.of(durations);
    }

    /** Retries the stage when attempts remain, otherwise records the final failure via {@code storeFailure}. */
    public ExecutionInfo retryOrFail(final ExecutionInfo executionInfo, final UUID resultNotificationId, final String error,
                                     final BiConsumer<UUID, String> storeFailure) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        final ExecutionInfo next;
        if (remaining != null && remaining > 1) {
            next = executionInfo().from(executionInfo).withExecutionStatus(INPROGRESS).withShouldRetry(true).build();
        } else {
            recordFailure(resultNotificationId, error, storeFailure);
            next = completed(executionInfo);
        }
        return next;
    }

    // the job ends either way: a failure to record it (database down) leaves the row in its last state for manual
    // recovery (FR-017) rather than a job that never runs again
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordFailure(final UUID resultNotificationId, final String error, final BiConsumer<UUID, String> storeFailure) {
        try {
            storeFailure.accept(resultNotificationId, error);
        } catch (RuntimeException e) {
            log.error("Enforcement result notification {}: failure {} could not be stored ({})", resultNotificationId, error,
                    e.getClass().getSimpleName());
        }
    }

    public static ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo).withExecutionStatus(COMPLETED).withShouldRetry(false).build();
    }
}
