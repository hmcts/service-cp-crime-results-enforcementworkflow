package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.HearingResultProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically marks SENDING rows older than the stale threshold as FAILED with an unknown outcome
 * (research.md R19). This covers attempts interrupted by a restart when the JMS message is never
 * redelivered. Worst case to a final outcome is threshold + interval (default about 6 min; FR-013).
 * Nothing is resent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleSubmissionSweeper {

    private final SubmissionStore store;
    private final HearingResultProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${cp.hearing-result.stale-sending-sweep-interval}")
    public void sweep() {
        final Instant cutoff = Instant.now(clock).minus(properties.getStaleSendingThreshold());
        final List<UUID> stale = store.findStaleSending(cutoff);
        stale.forEach(store::markInterrupted);
        if (!stale.isEmpty()) {
            log.warn("Marked {} stale SENDING submission(s) FAILED (outcome at GOB unknown): {}", stale.size(), stale);
        }
    }
}
