package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.EnforcementGatewayClient;
import uk.gov.hmcts.cp.client.GatewayResult;
import uk.gov.hmcts.cp.client.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.mapper.HearingResultedRequestMapper;
import uk.gov.hmcts.cp.mapper.MappingResult;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Turns one share's results into at most one GOB hearing-result submission per notification (feature 003: the
 * notification id is the submission's correlation id). The steps are:
 * select the enforcement case and defendant, key the share, resolve
 * the result codes, leave out offences already sent or adjourned (feature 002: each offence's final result is sent
 * once), choose the NOWS data items, build the request, record it as SENDING with the items it sends, call the
 * gateway, record the outcome, and mark GOB's data ready for the GOB data event (feature 003). A first-time share with
 * nothing new, and every amend &amp; reshare (never sent to GOB, feature 003 FR-008), reuses the most recent GOB data
 * instead of contacting GOB. Logs carry ids, {@code caseUrn}, codes, counts and outcomes, never payloads (FR-017).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HearingResultedProcessor {

    private static final String RESHARE = "reshare";

    private final EnforcementCaseSelector selector;
    private final ResultCodeResolver resultCodeResolver;
    private final OffenceResultFilter offenceResultFilter;
    private final NowsDataItemSelector nowsDataItemSelector;
    private final HearingResultedRequestMapper mapper;
    private final SubmissionStore store;
    private final EnforcementGatewayClient gatewayClient;
    private final NowsHandover nowsHandover;
    private final GobDataReuseHandover gobDataReuseHandover;

    public void process(final HearingResultedEvent event, final UUID notificationId) {
        switch (selector.select(event)) {
            case SelectionResult.Skipped skipped ->
                    log.info("Hearing {} not submitted to GOB: {}", event.hearing() == null ? null : event.hearing().id(),
                            skipped.reason());
            case SelectionResult.Selected selected -> {
                final SkipReason missing = missingShareAttribute(event);
                if (missing != null) {
                    // no share correlation id can be built (feature 002 research R18), or nothing to record the share time with
                    log.warn("Hearing {} caseUrn {} not submitted to GOB: {}", event.hearing().id(), caseUrn(selected), missing);
                } else {
                    processShare(event, selected, keyOf(event, selected, notificationId));
                }
            }
            case SelectionResult.Reshare reshare -> processReshare(event, reshare, notificationId);
        }
    }

    /**
     * Feature 002 FR-016, feature 003 FR-008/FR-009 (decided 2026-10-22): an amend &amp; reshare is never sent to GOB, even
     * when the first-time result never reached GOB, and leaves no submission row; the most recent GOB data for the case
     * and defendant is published with the reshare's request ids, or, with none, the notification ends NO_GOB_DATA. While a
     * first-time share of the hearing is still in progress the reshare is retried first, as that share may bring newer GOB data.
     */
    private void processReshare(final HearingResultedEvent event, final SelectionResult.Reshare reshare, final UUID notificationId) {
        // the hearing day comes from the notification and a reshare writes no row, so neither needs the shared time
        final SelectionResult.Selected selected = new SelectionResult.Selected(reshare.prosecutionCase(), reshare.defendant());
        final SubmissionKey key = keyOf(event, selected, notificationId);
        log.info("Hearing {} caseUrn {}: {}, never sent to GOB (FR-008); looking for GOB data already received", key.hearingId(),
                key.caseUrn(), RESHARE);
        // checked first: a first share committing its GOB data between the two reads must not leave the reshare without it
        nowsHandover.awaitFirstShare(key);
        if (!gobDataReuseHandover.handOver(key, reshare.defendant().masterDefendantId())) {
            nowsHandover.noGobData(key, "RESHARE_NO_GOB_DATA");
        }
    }

    private void processShare(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key) {
        store.findExisting(key.correlationId(), key.caseId(), key.defendantId())
                .ifPresentOrElse(existing -> onExisting(key, existing, selected.defendant().masterDefendantId()),
                        () -> processSelected(event, selected, key));
    }

    /**
     * A retry or replay of the same notification (the submission key is the notification id): never resent. A stale
     * SENDING row is marked interrupted (R19; open point with the BA/GOB). Otherwise the hand-over the earlier run
     * may not have reached is resumed from the recorded outcome, so GOB data already received is still published.
     */
    private void onExisting(final SubmissionKey key, final ExistingSubmission existing, final UUID masterDefendantId) {
        // only one job runs a notification, so its own SENDING row found by a later attempt was interrupted, however recent
        if (existing.status() == SubmissionStatus.SENDING) {
            store.markInterrupted(existing.id());
            log.warn("Submission {} caseUrn {} was left SENDING: marked FAILED (outcome at GOB unknown), not resent",
                    existing.id(), key.caseUrn());
        } else {
            log.info("Hearing {} caseUrn {} already submitted for this notification ({}); not sent again (R17)",
                    key.hearingId(), key.caseUrn(), existing.status());
            switch (existing.status()) {
                case SUCCEEDED -> store.findSucceeded(key.caseId(), key.defendantId()).stream()
                        .filter(s -> s.id().equals(existing.id()))
                        .filter(s -> NowsHandoverPayloadBuilder.nowsDataItemsOf(s.responsePayload()).isPresent())
                        .findFirst()
                        .ifPresent(s -> nowsHandover.handOver(s.id(), key, masterDefendantId,
                                GobReplies.accountNumberOf(s.requestPayload()).orElse(null), false));
                case SKIPPED_NOTHING_NEW -> gobDataReuseHandover.handOver(key, masterDefendantId);
                default -> {
                    // SENDING (not stale), FAILED, MAPPING_FAILED, SKIPPED_NO_RESULT_CODE (FR-010): nothing to hand over
                }
            }
        }
    }

    private void processSelected(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key) {
        try {
            processResolved(event, selected, key, resultCodeResolver.resolve(event, selected.defendant()));
        } catch (ReferenceDataUnavailableException e) {
            store.recordNotSent(key, "reference data unavailable (" + e.getCause().getClass().getSimpleName() + ")");
            log.warn("Hearing {} caseUrn {} not submitted to GOB: reference data unavailable (no retry, R12)",
                    key.hearingId(), key.caseUrn());
        }
    }

    private void processResolved(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key,
                                 final ResolvedCodes codes) {
        if (!codes.droppedCodes().isEmpty()) {
            log.info("Hearing {} caseUrn {}: result codes not sent to GOB {}", key.hearingId(), key.caseUrn(), codes.droppedCodes());
        }
        if (codes.gobCodes().isEmpty()) {
            // feature 003 FR-010 (replaces feature 002 FR-020): nothing to send to GOB and nothing to hearing-nows
            store.recordSkippedNoResultCode(key, codes.droppedCodes()).ifPresent(id ->
                    log.info("Hearing {} caseUrn {} not submitted to GOB: no result with a code (FR-010)", key.hearingId(), key.caseUrn()));
        } else {
            processFiltered(event, selected, key, codes, false);
        }
    }

    private void processFiltered(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key,
                                 final ResolvedCodes codes, final boolean refiltered) {
        final OffenceFilterResult filtered = offenceResultFilter.filter(key.caseId(), key.hearingDay(), codes);
        if (filtered.nothingToSend()) {
            nothingNew(selected, key, filtered);
        } else {
            final ResolvedCodes toSend = filtered.toSend();
            switch (mapper.map(event, selected, toSend, nowsDataItemSelector.select(toSend.cpShortCodes()))) {
                case MappingResult.Failed failed -> {
                    store.recordMappingFailed(key, failed.reason(), failed.detail());
                    log.warn("Hearing {} caseUrn {} not submitted to GOB: {} ({})", key.hearingId(), key.caseUrn(), failed.reason(),
                            failed.detail());
                }
                case MappingResult.Mapped mapped -> submit(event, selected, key, codes, filtered, mapped.request(), refiltered);
            }
        }
    }

    /** Feature 002 FR-020, feature 003 FR-009: GOB is not contacted; the most recent GOB data is reused, as for a reshare. */
    private void nothingNew(final SelectionResult.Selected selected, final SubmissionKey key, final OffenceFilterResult filtered) {
        store.recordSkippedNothingNew(key, "nothing new to send to GOB: " + filtered.summary()).ifPresent(id -> {
            log.info("Hearing {} caseUrn {} not submitted to GOB: nothing new ({})", key.hearingId(), key.caseUrn(), filtered.summary());
            gobDataReuseHandover.handOver(key, selected.defendant().masterDefendantId());
        });
    }

    private void submit(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key,
                        final ResolvedCodes codes, final OffenceFilterResult filtered, final HearingResultedRequest request,
                        final boolean refiltered) {
        switch (store.recordSending(key, PayloadJson.MAPPER.writeValueAsString(request), filtered.sentItems())) {
            case SendingRecord.Recorded recorded -> send(selected, key, recorded.submissionId(), request);
            case SendingRecord.AlreadyRecorded _ ->
                    log.info("Hearing {} caseUrn {} already recorded by a concurrent delivery; not sent again", key.hearingId(),
                            key.caseUrn());
            case SendingRecord.ItemsAlreadySent _ -> {
                if (refiltered) {
                    log.warn("Hearing {} caseUrn {}: offences were sent by another share meanwhile; not sent", key.hearingId(),
                            key.caseUrn());
                } else {
                    processFiltered(event, selected, key, codes, true);
                }
            }
        }
    }

    private void send(final SelectionResult.Selected selected, final SubmissionKey key, final UUID submissionId,
                      final HearingResultedRequest request) {
        // codes and a count only: the request itself carries defendant PII (FR-017)
        log.info("Submission {} caseUrn {}: sending to the enforcement gateway (results {}, NOWS data items {})", submissionId,
                request.getCaseUrn(), request.getResults().stream().map(result -> String.valueOf(result.getResultCode())).toList(),
                request.getNowsDataRequest() == null ? 0 : request.getNowsDataRequest().getNowsDataItems().size());
        switch (gatewayClient.submit(request)) {
            case GatewayResult.Success success -> {
                store.recordSucceeded(submissionId, success.rawResponse(), success.httpStatus());
                if (success.response() == null) {
                    log.warn("Submission {}: GOB accepted but its reply could not be parsed; raw reply stored, nothing handed over",
                            submissionId);
                } else {
                    if (!Objects.equals(success.response().getCaseUrn(), request.getCaseUrn())) {
                        log.warn("Submission {}: GOB response caseUrn does not match the caseUrn sent ({})", submissionId,
                                request.getCaseUrn());
                    }
                    log.info("Submission {} caseUrn {} accepted by GOB", submissionId, request.getCaseUrn());
                    handOverNew(selected, key, submissionId, request);
                }
            }
            case GatewayResult.Failure failure -> {
                store.recordFailed(submissionId, failure.httpStatus(), failure.detail());
                log.warn("Submission {} caseUrn {} failed: HTTP {} (no retry, R12)", submissionId, request.getCaseUrn(), failure.httpStatus());
            }
        }
    }

    private void handOverNew(final SelectionResult.Selected selected, final SubmissionKey key, final UUID submissionId,
                             final HearingResultedRequest request) {
        final String accountNumber = request.getDefendantDetails() == null ? null : request.getDefendantDetails().getProsecutorDefendantId();
        nowsHandover.handOver(submissionId, key, selected.defendant().masterDefendantId(), accountNumber, false);
    }

    private static SkipReason missingShareAttribute(final HearingResultedEvent event) {
        SkipReason reason = null;
        if (event.hearingDay() == null) {
            reason = SkipReason.HEARING_DAY_MISSING;
        } else if (event.sharedTime() == null) {
            reason = SkipReason.SHARED_TIME_MISSING;
        }
        return reason;
    }

    private static SubmissionKey keyOf(final HearingResultedEvent event, final SelectionResult.Selected selected, final UUID notificationId) {
        return new SubmissionKey(notificationId, event.hearing().id(),
                event.hearingDay(), selected.prosecutionCase().id(), selected.defendant().id(), caseUrn(selected), event.sharedTime());
    }

    private static String caseUrn(final SelectionResult.Selected selected) {
        return selected.prosecutionCase().prosecutionCaseIdentifier().caseURN();
    }
}
