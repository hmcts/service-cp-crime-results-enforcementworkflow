package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.client.HandoverResult;
import uk.gov.hmcts.cp.client.HearingNowsClient;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;

import java.util.List;
import java.util.UUID;

/**
 * Hands GOB's data for one share to hearing-nows and records the outcome on a submission row (feature 002 FR-001 to
 * FR-004). A failure is recorded and logged with ids, caseUrn and status only; it never sends anything to GOB again
 * and never stops the processing of later events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NowsHandover {

    private final NowsHandoverPayloadBuilder payloadBuilder;
    private final HearingNowsClient client;
    private final SubmissionStore store;

    /**
     * @param recordOnId    the submission row that records the outcome
     * @param detailPrefix  prefix of the recorded detail (e.g. {@code reshare}), or null
     */
    public void handOver(final UUID recordOnId, final SubmissionKey key, final UUID masterDefendantId, final String accountNumber,
                         final JsonNode nowsDataItems, final boolean gobDataReused, final List<UUID> coveredResultDefinitionIds,
                         final String detailPrefix) {
        if (masterDefendantId == null || accountNumber == null) {
            final String reason = masterDefendantId == null ? "no masterDefendantId" : "no account number";
            store.recordHandover(recordOnId, NowsHandoverStatus.FAILED, prefixed(detailPrefix, reason));
            log.warn("Submission {} caseUrn {}: GOB data not handed over to hearing NOWS ({})", recordOnId, key.caseUrn(), reason);
        } else {
            final String payload = payloadBuilder.build(key, masterDefendantId, accountNumber, nowsDataItems, gobDataReused,
                    coveredResultDefinitionIds);
            switch (client.handOver(payload)) {
                case HandoverResult.HandedOver _ -> {
                    store.recordHandover(recordOnId, NowsHandoverStatus.HANDED_OVER, detailPrefix);
                    log.info("Submission {} caseUrn {}: GOB data handed over to hearing NOWS (correlationId {}, reused {})",
                            recordOnId, key.caseUrn(), key.correlationId(), gobDataReused);
                }
                case HandoverResult.Failed failed -> {
                    store.recordHandover(recordOnId, NowsHandoverStatus.FAILED, prefixed(detailPrefix, failed.detail()));
                    log.warn("Submission {} caseUrn {}: hand-over to hearing NOWS failed: {} (not retried, GOB not contacted again)",
                            recordOnId, key.caseUrn(), failed.detail());
                }
            }
        }
    }

    private static String prefixed(final String prefix, final String detail) {
        return prefix == null ? detail : prefix + ": " + detail;
    }
}
