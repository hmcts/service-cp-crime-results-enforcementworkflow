package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hands over, without contacting GOB, the most recent GOB data received for a case and defendant (feature 002
 * FR-016, FR-020, research R16): for an amend &amp; reshare, and for a share with nothing new to send. The covered
 * results are the share's results whose GOB code was in a request GOB accepted; hearing-nows completes an amended
 * NOW only for a covered result type (FR-017). With no accepted submission, nothing is handed over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GobDataReuseHandover {

    private final SubmissionStore store;
    private final NowsHandover nowsHandover;

    /**
     * @param recordOnId    the row recording the outcome, or null to record it on the reused submission
     * @param detailPrefix  prefix of the recorded detail (e.g. {@code reshare}), or null
     */
    public void handOver(final SubmissionKey key, final UUID masterDefendantId, final ResolvedCodes shareCodes, final UUID recordOnId,
                         final String detailPrefix) {
        final List<SucceededSubmission> accepted = store.findSucceeded(key.caseId(), key.defendantId());
        final Optional<SucceededSubmission> latest = accepted.stream()
                .filter(s -> NowsHandoverPayloadBuilder.nowsDataItemsOf(s.responsePayload()).isPresent())
                .findFirst();
        if (latest.isEmpty()) {
            log.info("Hearing {} caseUrn {}: no GOB data to reuse (GOB accepted no submission for the case and defendant); nothing handed over",
                    key.hearingId(), key.caseUrn());
        } else {
            final SucceededSubmission source = latest.get();
            final Set<String> sentCodes = new HashSet<>();
            accepted.forEach(s -> sentCodes.addAll(resultCodesOf(s.requestPayload())));
            final List<UUID> covered = shareCodes.results().stream()
                    .filter(r -> r.gobCode() != null && sentCodes.contains(r.gobCode().getValue()))
                    .map(ResolvedResult::judicialResultTypeId)
                    .distinct()
                    .toList();
            nowsHandover.handOver(recordOnId != null ? recordOnId : source.id(), key, masterDefendantId,
                    accountNumberOf(source.requestPayload()), NowsHandoverPayloadBuilder.nowsDataItemsOf(source.responsePayload()).orElseThrow(),
                    true, covered, detailPrefix);
        }
    }

    private static Set<String> resultCodesOf(final String request) {
        final Set<String> codes = new HashSet<>();
        readTree(request).ifPresent(json -> json.path("results").forEach(result -> codes.add(result.path("resultCode").asString(""))));
        return codes;
    }

    /** The GOB account number: the prosecutorDefendantId sent to GOB (feature 001 research R9). */
    private static String accountNumberOf(final String request) {
        return readTree(request).map(json -> json.path("defendantDetails").path("prosecutorDefendantId"))
                .filter(JsonNode::isString).map(JsonNode::asString).orElse(null);
    }

    private static Optional<JsonNode> readTree(final String json) {
        Optional<JsonNode> tree = Optional.empty();
        if (json != null) {
            try {
                tree = Optional.of(PayloadJson.MAPPER.readTree(json));
            } catch (JacksonException e) {
                tree = Optional.empty();
            }
        }
        return tree;
    }
}
