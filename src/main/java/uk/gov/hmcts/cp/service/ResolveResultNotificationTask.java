package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.ProsecutionCase;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.service.ResultNotificationTasks.RESOLVE_RESULT_NOTIFICATION;
import static uk.gov.hmcts.cp.service.ResultNotificationTasks.triggerNotificationIdOf;

/**
 * First stage of feature 005's own, independent task chain (research.md R4, FR-010): reads the trigger
 * {@link EnforcementNotificationEntity} (feature 003's own row, read-only here), re-reads the share's results
 * (deliberately duplicating {@code ProcessNotificationTask}'s own call rather than sharing state - the independence
 * requirement), runs the existing {@link EnforcementCaseSelector} as-is, and idempotently creates one
 * {@code enforcement_result_notification} row per unique {@code relatedReferenceNumber}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(RESOLVE_RESULT_NOTIFICATION)
public class ResolveResultNotificationTask implements ExecutableTask {

    private final NotificationStore notificationStore;
    private final HearingResultsSource resultsSource;
    private final EnforcementCaseSelector caseSelector;
    private final ResultNotificationStore store;
    private final ResultNotificationTasks tasks;
    private final ResultNotificationRetries retries;
    private final ResultNotificationProperties properties;

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID triggerNotificationId = triggerNotificationIdOf(executionInfo.getJobData());
        ExecutionInfo next;
        try {
            next = resolve(executionInfo, triggerNotificationId);
        } catch (RuntimeException e) {
            log.error("Enforcement result notification resolve for trigger {} failed ({})", triggerNotificationId,
                    e.getClass().getSimpleName());
            next = retries.retryOrFail(executionInfo, triggerNotificationId, e.getClass().getSimpleName(), this::logExhausted);
        }
        return next;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    private ExecutionInfo resolve(final ExecutionInfo executionInfo, final UUID triggerNotificationId) {
        final Optional<EnforcementNotificationEntity> trigger = notificationStore.find(triggerNotificationId);
        final ExecutionInfo next;
        if (trigger.isEmpty()) {
            log.warn("Enforcement result notification resolve: trigger notification {} not found", triggerNotificationId);
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            final Optional<HearingResultedEvent> event = resultsSource.sharedResults(trigger.get().getHearingId(),
                    trigger.get().getHearingDate(), trigger.get().isReshare());
            if (event.isEmpty()) {
                next = retries.retryOrFail(executionInfo, triggerNotificationId, "RESULTS_NOT_READY", this::logExhausted);
            } else {
                createRowIfSelected(trigger.get(), event.get());
                next = ResultNotificationRetries.completed(executionInfo);
            }
        }
        return next;
    }

    private void createRowIfSelected(final EnforcementNotificationEntity trigger, final HearingResultedEvent event) {
        final SelectionResult selection = caseSelector.select(event);
        final Optional<ProsecutionCase> prosecutionCase;
        final Optional<Defendant> defendant;
        if (selection instanceof SelectionResult.Selected selected) {
            prosecutionCase = Optional.of(selected.prosecutionCase());
            defendant = Optional.of(selected.defendant());
        } else if (selection instanceof SelectionResult.Reshare reshare) {
            prosecutionCase = Optional.of(reshare.prosecutionCase());
            defendant = Optional.of(reshare.defendant());
        } else {
            prosecutionCase = Optional.empty();
            defendant = Optional.empty();
        }
        // prosecutionCase and defendant are always set together above, never one without the other
        if (defendant.isPresent()) {
            final NotificationType notificationType = Boolean.TRUE.equals(event.isReshare())
                    ? NotificationType.AMEND_RESHARE : NotificationType.FIRST_SHARE;
            final String caseUrn = prosecutionCase.get().prosecutionCaseIdentifier().caseURN();
            final String relatedReferenceNumber = defendant.get().prosecutionAuthorityReference();
            final DivisionCode divisionCode = DivisionCode.from(relatedReferenceNumber);
            store.createRequested(trigger.getId(), trigger.getHearingId(), trigger.getHearingDate(),
                    relatedReferenceNumber, notificationType, caseUrn, divisionCode.value(), divisionCode.areaFor(properties),
                    prosecutionCase.get().id())
                    .ifPresent(this::queueMatch);
        } else {
            log.info("Enforcement result notification resolve: hearing {} out of scope, no row created", event.hearing().id());
        }
    }

    private void queueMatch(final EnforcementResultNotificationEntity entity) {
        tasks.queueMatch(entity.getId());
    }

    // no enforcement_result_notification row exists yet at this stage: a resolve retry exhaustion has nothing to
    // record against, so it is logged only - the hearing capture itself is never blocked either way (SC-004)
    private void logExhausted(final UUID triggerNotificationId, final String error) {
        log.error("Enforcement result notification resolve for trigger {} exhausted its retries: {}", triggerNotificationId, error);
    }
}
