package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.DocGeneratorClient;
import uk.gov.hmcts.cp.client.EnforcementAreaClient;
import uk.gov.hmcts.cp.client.GenerateDocumentCommand;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.client.TeamInbox;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.service.ResultNotificationTasks.MATCH_RESULT_NOTIFICATION;
import static uk.gov.hmcts.cp.service.ResultNotificationTasks.resultNotificationIdOf;

/**
 * Second stage of feature 005's own task chain: the FR-011 gate (re-evaluated fresh against the hearing's current
 * state for both notification types - Clarification 2026-11-24), Division Code resolution, the
 * {@code enforcement_area} team-inbox lookup, and - once both pass - building and uploading the render payload and
 * sending {@code generate-document}. For AMEND_RESHARE, also reads the Previous Share Snapshot (research.md R3) once
 * the gate passes, never before.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(MATCH_RESULT_NOTIFICATION)
public class MatchResultNotificationTask implements ExecutableTask {

    private static final String ORIGINATING_SOURCE = "service-cp-crime-results-enforcementworkflow";

    private final ResultNotificationStore store;
    private final NotificationStore triggerStore;
    private final HearingResultsSource resultsSource;
    private final EnforcementCaseSelector caseSelector;
    private final ResultCodeResolver resultCodeResolver;
    private final TriggerShortCodeMatcher triggerShortCodeMatcher;
    private final EnforcementAreaClient enforcementAreaClient;
    private final DocGeneratorClient docGeneratorClient;
    private final ResultNotificationBlobClient blobClient;
    private final ResultNotificationPayloadBuilder payloadBuilder;
    private final ResultNotificationProperties properties;
    private final ResultNotificationRetries retries;

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID id = resultNotificationIdOf(executionInfo.getJobData());
        ExecutionInfo next;
        try {
            next = match(executionInfo, id);
        } catch (RuntimeException e) {
            log.error("Enforcement result notification {} match failed ({})", id, e.getClass().getSimpleName());
            next = retries.retryOrFail(executionInfo, id, e.getClass().getSimpleName(), this::markDocFailed);
        }
        return next;
    }

    // FR-016: DOC_FAILED is also a notification outcome that must be logged with caseUrn, Division Code, team
    // email, shortCodes and the sharing user's identifier - whichever of these this row had already resolved
    private void markDocFailed(final UUID id, final String error) {
        store.markDocFailed(id, error);
        store.find(id).ifPresent(row -> log.info("Enforcement result notification {} DOC_FAILED: caseUrn={}, divisionCode={}, "
                + "area={}, teamEmail={}, resultShortCodes={}, sharingUser={}, error={}, at={}", id, row.getCaseUrn(),
                row.getDivisionCode(), row.getArea(), row.getTeamEmail(), row.getResultShortCodes(), callerOf(row), error, Instant.now()));
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    private ExecutionInfo match(final ExecutionInfo executionInfo, final UUID id) {
        final Optional<EnforcementResultNotificationEntity> row = store.find(id);
        final ExecutionInfo next;
        if (row.isEmpty() || row.get().getStatus() != ResultNotificationStatus.REQUESTED) {
            log.info("Enforcement result notification {}: nothing to match ({})", id,
                    row.map(EnforcementResultNotificationEntity::getStatus).orElse(null));
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            final Optional<HearingResultedEvent> event = resultsSource.sharedResults(row.get().getHearingId(),
                    row.get().getHearingDate(), row.get().getNotificationType() == NotificationType.AMEND_RESHARE);
            next = event.isEmpty()
                    ? retries.retryOrFail(executionInfo, id, "RESULTS_NOT_READY", this::markDocFailed)
                    : matchAgainstEvent(executionInfo, row.get(), event.get());
        }
        return next;
    }

    private ExecutionInfo matchAgainstEvent(final ExecutionInfo executionInfo, final EnforcementResultNotificationEntity row,
                                            final HearingResultedEvent event) {
        final Optional<Defendant> defendant = selectDefendant(event);
        final ExecutionInfo next;
        if (defendant.isEmpty()) {
            log.warn("Enforcement result notification {}: hearing {} no longer in scope", row.getId(), event.hearing().id());
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            final ResolvedCodes resolved = resultCodeResolver.resolve(event, defendant.get());
            final DivisionCode divisionCode = DivisionCode.from(row.getRelatedReferenceNumber());
            final Area area = divisionCode.areaFor(properties);
            next = triggerShortCodeMatcher.matches(resolved.results(), area)
                    ? resolveInboxAndDispatch(executionInfo, row, defendant.get(), resolved, divisionCode, area)
                    : suppress(executionInfo, row, divisionCode, area, resolved);
        }
        return next;
    }

    private Optional<Defendant> selectDefendant(final HearingResultedEvent event) {
        final SelectionResult selection = caseSelector.select(event);
        final Optional<Defendant> defendant;
        if (selection instanceof SelectionResult.Selected selected) {
            defendant = Optional.of(selected.defendant());
        } else if (selection instanceof SelectionResult.Reshare reshare) {
            defendant = Optional.of(reshare.defendant());
        } else {
            defendant = Optional.empty();
        }
        return defendant;
    }

    private ExecutionInfo suppress(final ExecutionInfo executionInfo, final EnforcementResultNotificationEntity row,
                                   final DivisionCode divisionCode, final Area area, final ResolvedCodes resolved) {
        store.markSuppressed(row.getId());
        // FR-016: SUPPRESSED logs the full current-state shortCode set (not an empty array) - nothing matched the
        // trigger list, so this is what was reviewed and found non-qualifying.
        log.info("Enforcement result notification {} SUPPRESSED: caseUrn={}, divisionCode={}, area={}, shortCodes={}, "
                + "sharingUser={}, at={}", row.getId(), row.getCaseUrn(), divisionCode.value(), area, resolved.cpShortCodes(),
                callerOf(row), Instant.now());
        return ResultNotificationRetries.completed(executionInfo);
    }

    private ExecutionInfo resolveInboxAndDispatch(final ExecutionInfo executionInfo, final EnforcementResultNotificationEntity row,
                                                  final Defendant defendant, final ResolvedCodes resolved,
                                                  final DivisionCode divisionCode, final Area area) {
        final Optional<TeamInbox> inbox = enforcementAreaClient.findTeamInbox(divisionCode.asInt());
        final ExecutionInfo next;
        if (inbox.isEmpty()) {
            store.markInboxNotFound(row.getId(), divisionCode.value(), area);
            log.info("Enforcement result notification {} INBOX_NOT_FOUND: caseUrn={}, divisionCode={}, area={}, "
                    + "sharingUser={}, at={}", row.getId(), row.getCaseUrn(), divisionCode.value(), area, callerOf(row), Instant.now());
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            dispatchDocumentGeneration(row, defendant, resolved, divisionCode, area, inbox.get());
            next = ResultNotificationRetries.completed(executionInfo);
        }
        return next;
    }

    private void dispatchDocumentGeneration(final EnforcementResultNotificationEntity row,
                                            final Defendant defendant, final ResolvedCodes resolved, final DivisionCode divisionCode,
                                            final Area area, final TeamInbox inbox) {
        final String currentShortCodesJson = payloadBuilder.buildResultShortCodes(defendant, resolved, area);
        final boolean isReshare = row.getNotificationType() == NotificationType.AMEND_RESHARE;
        final Optional<EnforcementResultNotificationEntity> previous = isReshare
                ? store.previousShareSnapshot(row.getHearingId(), row.getRelatedReferenceNumber()) : Optional.empty();
        final String docGenPayload = isReshare
                ? payloadBuilder.buildDocGenPayload(row.getNotificationType(), row.getHearingId(), row.getHearingDate(),
                        row.getCaseUrn(), row.getRelatedReferenceNumber(), currentShortCodesJson,
                        previous.map(EnforcementResultNotificationEntity::getResultShortCodes).orElse(null), row.getReceivedAt())
                : payloadBuilder.buildDocGenPayload(row.getNotificationType(), row.getHearingId(), row.getHearingDate(),
                        row.getCaseUrn(), row.getRelatedReferenceNumber(), currentShortCodesJson);
        final String payloadFileUri = blobClient.uploadPayload(row.getId(), row.getNotificationType(), docGenPayload);
        final String destinationFileUri = blobClient.destinationUrl(row.getId());
        store.markDocRequested(row.getId(), divisionCode.value(), area, inbox.teamEmail(), currentShortCodesJson,
                payloadFileUri, destinationFileUri);
        docGeneratorClient.generateDocument(new GenerateDocumentCommand(
                properties.getDocmosisTemplateIds().get(row.getNotificationType()), GenerateDocumentCommand.CONVERSION_FORMAT_PDF,
                Instant.now(), payloadFileUri, destinationFileUri, ORIGINATING_SOURCE, row.getId()));
    }

    private String callerOf(final EnforcementResultNotificationEntity row) {
        return triggerStore.find(row.getTriggerNotificationId()).map(uk.gov.hmcts.cp.entity.EnforcementNotificationEntity::getCaller)
                .orElse(null);
    }
}
