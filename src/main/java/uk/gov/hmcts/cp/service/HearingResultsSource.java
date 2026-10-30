package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.client.ResultsCacheClient;
import uk.gov.hmcts.cp.client.ResultsClient;
import uk.gov.hmcts.cp.client.ResultsUnavailableException;
import uk.gov.hmcts.cp.event.HearingResultedEvent;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The share's results for stage 1 (feature 003, FR-006, research R7): the Results cache by hearing and day when it is
 * enabled and holds them, otherwise the Results query. Both are mapped to the {@link HearingResultedEvent} that features
 * 001 and 002 process. The hearing day and the reshare flag always come from the notification (research R22): the
 * Results query carries neither, and an amend &amp; reshare is never sent to GOB (FR-008). Empty means not ready yet (no
 * hearing with prosecution cases), so the stage is retried.
 */
@Slf4j
@Component
public class HearingResultsSource {

    private final ObjectProvider<ResultsCacheClient> cacheClient;
    private final ResultsClient resultsClient;
    private final ObjectMapper objectMapper;

    public HearingResultsSource(final ObjectProvider<ResultsCacheClient> cacheClient, final ResultsClient resultsClient,
                                final ObjectMapper objectMapper) {
        this.cacheClient = cacheClient;
        this.resultsClient = resultsClient;
        this.objectMapper = objectMapper;
    }

    /** @throws ResultsUnavailableException when Results can't be read */
    public Optional<HearingResultedEvent> sharedResults(final UUID hearingId, final LocalDate hearingDay, final boolean isReshare) {
        final ResultsCacheClient cache = cacheClient.getIfAvailable();
        Optional<HearingResultedEvent> event = Optional.ofNullable(cache).flatMap(c -> c.sharedResults(hearingId, hearingDay))
                .flatMap(json -> parseCached(json, hearingId, hearingDay, isReshare))
                .filter(HearingResultsSource::isReady);
        if (event.isEmpty()) {
            event = resultsClient.hearingDetails(hearingId)
                    .map(json -> parse(json, hearingId, hearingDay, isReshare))
                    .filter(HearingResultsSource::isReady);
        } else {
            log.info("Results for hearing {} on {} read from the Results cache", hearingId, hearingDay);
        }
        return event;
    }

    /** An unreadable cache value is a miss: the Results query is the source of truth. */
    private Optional<HearingResultedEvent> parseCached(final String json, final UUID hearingId, final LocalDate hearingDay,
                                                       final boolean isReshare) {
        Optional<HearingResultedEvent> event;
        try {
            event = Optional.of(parse(json, hearingId, hearingDay, isReshare));
        } catch (ResultsUnavailableException e) {
            log.warn("Results cache value for hearing {} on {} not readable; using the Results query", hearingId, hearingDay);
            event = Optional.empty();
        }
        return event;
    }

    private HearingResultedEvent parse(final String json, final UUID hearingId, final LocalDate hearingDay, final boolean isReshare) {
        try {
            final HearingResultedEvent read = objectMapper.readValue(json, HearingResultedEvent.class);
            return new HearingResultedEvent(read.hearing(), isReshare, read.sharedTime(), hearingDay);
        } catch (JacksonException e) {
            // the message can quote the payload (personal data): only the exception class is kept
            throw new ResultsUnavailableException("Results for hearing " + hearingId + " are not readable: "
                    + e.getClass().getSimpleName(), null);
        }
    }

    private static boolean isReady(final HearingResultedEvent event) {
        return event.hearing() != null && event.hearing().prosecutionCases() != null && !event.hearing().prosecutionCases().isEmpty();
    }
}
