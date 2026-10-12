package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.EnforcementGatewayClient;
import uk.gov.hmcts.cp.client.GatewayResult;
import uk.gov.hmcts.cp.client.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.mapper.HearingResultedRequestMapper;
import uk.gov.hmcts.cp.mapper.MappingResult;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Turns one hearing-resulted event into at most one GOB hearing-result submission per share. The steps are:
 * select the enforcement case and defendant, key the share (correlation id = hearing id + hearing day), resolve
 * the result codes, leave out offences already sent or adjourned (feature 002: each offence's final result is sent
 * once), choose the NOWS data items, build the request, record it as SENDING with the items it sends, call the
 * gateway, record the outcome, and hand GOB's data to hearing-nows. A share with nothing new reuses the most recent
 * GOB data instead of contacting GOB. Logs carry ids, {@code caseUrn}, codes, counts and outcomes, never payloads
 * (FR-017).
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

    public void process(final HearingResultedEvent event) {
        switch (selector.select(event)) {
            case SelectionResult.Skipped skipped ->
                    log.info("Hearing {} not submitted to GOB: {}", event.hearing() == null ? null : event.hearing().id(),
                            skipped.reason());
            case SelectionResult.Selected selected -> {
                if (event.hearingDay() == null) {
                    // no share correlation id can be built (feature 002 research R18)
                    log.warn("Hearing {} caseUrn {} not submitted to GOB: {}", event.hearing().id(), caseUrn(selected),
                            SkipReason.HEARING_DAY_MISSING);
                } else {
                    processShare(event, selected, keyOf(event, selected));
                }
            }
            case SelectionResult.Reshare reshare -> processReshare(event, reshare);
        }
    }

    /**
     * Feature 002 FR-016, research R16: an amend &amp; reshare is never sent to GOB and leaves no submission row; the most
     * recent GOB data for the case and defendant is handed over again with the reshare's covered results, recorded on
     * the submission whose data is reused (detail prefix {@code reshare}).
     */
    private void processReshare(final HearingResultedEvent event, final SelectionResult.Reshare reshare) {
        final SelectionResult.Selected selected = new SelectionResult.Selected(reshare.prosecutionCase(), reshare.defendant());
        if (event.hearingDay() == null) {
            log.warn("Hearing {} caseUrn {} reshare not handed over: {}", event.hearing().id(), caseUrn(selected), SkipReason.HEARING_DAY_MISSING);
            return;
        }
        final SubmissionKey key = keyOf(event, selected);
        log.info("Hearing {} caseUrn {}: reshare, not sent to GOB (R2); reusing the GOB data already received", key.hearingId(),
                key.caseUrn());
        try {
            gobDataReuseHandover.handOver(key, reshare.defendant().masterDefendantId(),
                    resultCodeResolver.resolve(event, reshare.defendant()), null, RESHARE);
        } catch (ReferenceDataUnavailableException e) {
            log.warn("Hearing {} caseUrn {} reshare not handed over: reference data unavailable (no retry, R12)", key.hearingId(),
                    key.caseUrn());
        }
    }

    private void processShare(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key) {
        store.findExisting(key.correlationId(), key.caseId(), key.defendantId())
                .ifPresentOrElse(existing -> onExisting(key, existing), () -> processSelected(event, selected, key));
    }

    /** A repeated delivery of the same share: never resent. A stale SENDING row is marked interrupted (R19; open point with the BA/GOB). */
    private void onExisting(final SubmissionKey key, final ExistingSubmission existing) {
        if (store.isStale(existing)) {
            store.markInterrupted(existing.id());
            log.warn("Submission {} caseUrn {} was left SENDING: marked FAILED (outcome at GOB unknown), not resent",
                    existing.id(), key.caseUrn());
        } else {
            log.info("Hearing {} caseUrn {} already submitted for this share ({}); repeated delivery ignored (R17)",
                    key.hearingId(), key.caseUrn(), existing.status());
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
            store.recordSkippedNoResultCode(key, codes.droppedCodes());
            log.info("Hearing {} caseUrn {} not submitted to GOB: no GOB-recognised result code", key.hearingId(), key.caseUrn());
        } else {
            processFiltered(event, selected, key, codes, false);
        }
    }

    private void processFiltered(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key,
                                 final ResolvedCodes codes, final boolean refiltered) {
        final OffenceFilterResult filtered = offenceResultFilter.filter(key.caseId(), key.hearingDay(), codes);
        if (filtered.nothingToSend()) {
            nothingNew(selected, key, codes, filtered);
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

    /** Feature 002 FR-020: GOB is not contacted; the most recent GOB data is handed over again, as for a reshare. */
    private void nothingNew(final SelectionResult.Selected selected, final SubmissionKey key, final ResolvedCodes codes,
                            final OffenceFilterResult filtered) {
        store.recordSkippedNothingNew(key, "nothing new to send to GOB: " + filtered.summary()).ifPresent(id -> {
            log.info("Hearing {} caseUrn {} not submitted to GOB: nothing new ({})", key.hearingId(), key.caseUrn(), filtered.summary());
            gobDataReuseHandover.handOver(key, selected.defendant().masterDefendantId(), codes, id, null);
        });
    }

    private void submit(final HearingResultedEvent event, final SelectionResult.Selected selected, final SubmissionKey key,
                        final ResolvedCodes codes, final OffenceFilterResult filtered, final HearingResultedRequest request,
                        final boolean refiltered) {
        switch (store.recordSending(key, PayloadJson.MAPPER.writeValueAsString(request), filtered.sentItems())) {
            case SendingRecord.Recorded recorded -> send(selected, key, recorded.submissionId(), request, filtered.toSend());
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
                      final HearingResultedRequest request, final ResolvedCodes sent) {
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
                    handOverNew(selected, key, submissionId, request, success.rawResponse(), sent);
                }
            }
            case GatewayResult.Failure failure -> {
                store.recordFailed(submissionId, failure.httpStatus(), failure.detail());
                log.warn("Submission {} caseUrn {} failed: HTTP {} (no retry, R12)", submissionId, request.getCaseUrn(), failure.httpStatus());
            }
        }
    }

    private void handOverNew(final SelectionResult.Selected selected, final SubmissionKey key, final UUID submissionId,
                             final HearingResultedRequest request, final String rawReply, final ResolvedCodes sent) {
        final String accountNumber = request.getDefendantDetails() == null ? null : request.getDefendantDetails().getProsecutorDefendantId();
        nowsHandover.handOver(submissionId, key, selected.defendant().masterDefendantId(), accountNumber,
                NowsHandoverPayloadBuilder.nowsDataItemsOf(rawReply).orElseThrow(), false, sent.gobResultDefinitionIds(), null);
    }

    private static SubmissionKey keyOf(final HearingResultedEvent event, final SelectionResult.Selected selected) {
        return new SubmissionKey(ShareCorrelationId.forShare(event.hearing().id(), event.hearingDay()), event.hearing().id(),
                event.hearingDay(), selected.prosecutionCase().id(), selected.defendant().id(), caseUrn(selected), event.sharedTime());
    }

    private static String caseUrn(final SelectionResult.Selected selected) {
        return selected.prosecutionCase().prosecutionCaseIdentifier().caseURN();
    }
}
