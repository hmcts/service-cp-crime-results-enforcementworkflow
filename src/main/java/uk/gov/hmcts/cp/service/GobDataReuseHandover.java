package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reuses, without contacting GOB, the most recent GOB data received for a case and defendant (feature 002 FR-016,
 * FR-020; feature 003 FR-009): for an amend &amp; reshare and for a share with nothing new to send. With no accepted
 * submission there is nothing to reuse.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GobDataReuseHandover {

    private final SubmissionStore store;
    private final NowsHandover nowsHandover;

    /** @return true when earlier GOB data was found and handed over */
    public boolean handOver(final SubmissionKey key, final UUID masterDefendantId) {
        final List<SucceededSubmission> accepted = store.findSucceeded(key.caseId(), key.defendantId());
        final Optional<SucceededSubmission> latest = accepted.stream()
                .filter(s -> NowsHandoverPayloadBuilder.nowsDataItemsOf(s.responsePayload()).isPresent())
                .findFirst();
        if (latest.isEmpty()) {
            log.info("Hearing {} caseUrn {}: no GOB data to reuse (GOB accepted no submission for the case and defendant)",
                    key.hearingId(), key.caseUrn());
        } else {
            final SucceededSubmission source = latest.get();
            nowsHandover.handOver(source.id(), key, masterDefendantId, GobReplies.accountNumberOf(source.requestPayload()).orElse(null),
                    true);
        }
        return latest.isPresent();
    }
}
