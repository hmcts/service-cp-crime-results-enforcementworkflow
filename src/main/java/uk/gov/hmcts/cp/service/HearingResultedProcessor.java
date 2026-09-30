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
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one hearing-resulted event into at most one GOB hearing-result submission. The steps are:
 * select the enforcement case and defendant, resolve the result codes, choose the NOWS data items,
 * build the request, record it
 * as SENDING, call the gateway, and record the outcome. Logs carry ids, {@code caseUrn} and the
 * outcome, never payloads (FR-017).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HearingResultedProcessor {

    private final EnforcementCaseSelector selector;
    private final ResultCodeResolver resultCodeResolver;
    private final NowsDataItemSelector nowsDataItemSelector;
    private final HearingResultedRequestMapper mapper;
    private final SubmissionStore store;
    private final EnforcementGatewayClient gatewayClient;

    public void process(final HearingResultedEvent event) {
        switch (selector.select(event)) {
            case SelectionResult.Skipped skipped ->
                    log.info("Hearing {} not submitted to GOB: {}", event.hearing() == null ? null : event.hearing().id(),
                            skipped.reason());
            case SelectionResult.Selected selected -> store.findExisting(event.hearing().id(), selected.prosecutionCase().id(),
                            selected.defendant().id())
                    .ifPresentOrElse(existing -> onExisting(event, selected, existing), () -> processSelected(event, selected));
        }
    }

    /** A repeated delivery: never resent. A stale SENDING row is marked interrupted (R19; open point with the BA/GOB). */
    private void onExisting(final HearingResultedEvent event, final SelectionResult.Selected selected, final ExistingSubmission existing) {
        if (store.isStale(existing)) {
            store.markInterrupted(existing.id());
            log.warn("Submission {} caseUrn {} was left SENDING: marked FAILED (outcome at GOB unknown), not resent",
                    existing.id(), caseUrn(selected));
        } else {
            log.info("Hearing {} caseUrn {} already submitted ({}); repeated delivery ignored (R17)",
                    event.hearing().id(), caseUrn(selected), existing.status());
        }
    }

    private void processSelected(final HearingResultedEvent event, final SelectionResult.Selected selected) {
        try {
            processResolved(event, selected, resultCodeResolver.resolve(event, selected.defendant()));
        } catch (ReferenceDataUnavailableException e) {
            store.recordNotSent(event.hearing().id(), selected.prosecutionCase().id(), selected.defendant().id(),
                    caseUrn(selected), event.sharedTime(), "reference data unavailable (" + e.getCause().getClass().getSimpleName() + ")");
            log.warn("Hearing {} caseUrn {} not submitted to GOB: reference data unavailable (no retry, R12)",
                    event.hearing().id(), caseUrn(selected));
        }
    }

    private void processResolved(final HearingResultedEvent event, final SelectionResult.Selected selected, final ResolvedCodes codes) {
        if (!codes.droppedCodes().isEmpty()) {
            log.info("Hearing {} caseUrn {}: result codes not sent to GOB {}", event.hearing().id(), caseUrn(selected), codes.droppedCodes());
        }
        if (codes.gobCodes().isEmpty()) {
            store.recordSkippedNoResultCode(event.hearing().id(), selected.prosecutionCase().id(), selected.defendant().id(),
                    caseUrn(selected), event.sharedTime(), codes.droppedCodes());
            log.info("Hearing {} caseUrn {} not submitted to GOB: no GOB-recognised result code", event.hearing().id(), caseUrn(selected));
        } else {
            switch (mapper.map(event, selected, codes, nowsDataItemSelector.select(codes.cpShortCodes()))) {
                case MappingResult.Failed failed -> {
                    store.recordMappingFailed(event.hearing().id(), selected.prosecutionCase().id(), selected.defendant().id(),
                            caseUrn(selected), event.sharedTime(), failed.reason(), failed.detail());
                    log.warn("Hearing {} caseUrn {} not submitted to GOB: {} ({})",
                            event.hearing().id(), caseUrn(selected), failed.reason(), failed.detail());
                }
                case MappingResult.Mapped mapped -> submit(event, selected, mapped.request());
            }
        }
    }

    private void submit(final HearingResultedEvent event, final SelectionResult.Selected selected, final HearingResultedRequest request) {
        final Optional<UUID> recorded = store.recordSending(event.hearing().id(), selected.prosecutionCase().id(),
                selected.defendant().id(), request.getCaseUrn(), event.sharedTime(), PayloadJson.MAPPER.writeValueAsString(request));
        recorded.ifPresentOrElse(submissionId -> send(submissionId, request),
                () -> log.info("Hearing {} caseUrn {} already recorded by a concurrent delivery; not sent again",
                        event.hearing().id(), request.getCaseUrn()));
    }

    private void send(final UUID submissionId, final HearingResultedRequest request) {
        switch (gatewayClient.submit(request)) {
            case GatewayResult.Success success -> {
                store.recordSucceeded(submissionId, success.rawResponse(), success.httpStatus());
                if (success.response() == null) {
                    log.warn("Submission {}: GOB accepted but its reply could not be parsed; raw reply stored", submissionId);
                } else if (!Objects.equals(success.response().getCaseUrn(), request.getCaseUrn())) {
                    log.warn("Submission {}: GOB response caseUrn does not match the caseUrn sent ({})", submissionId, request.getCaseUrn());
                }
                log.info("Submission {} caseUrn {} accepted by GOB", submissionId, request.getCaseUrn());
            }
            case GatewayResult.Failure failure -> {
                store.recordFailed(submissionId, failure.httpStatus(), failure.detail());
                log.warn("Submission {} caseUrn {} failed: HTTP {} (no retry, R12)", submissionId, request.getCaseUrn(), failure.httpStatus());
            }
        }
    }

    private static String caseUrn(final SelectionResult.Selected selected) {
        return selected.prosecutionCase().prosecutionCaseIdentifier().caseURN();
    }
}
