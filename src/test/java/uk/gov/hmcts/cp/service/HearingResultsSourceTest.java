package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.client.ResultsCacheClient;
import uk.gov.hmcts.cp.client.ResultsClient;
import uk.gov.hmcts.cp.client.ResultsUnavailableException;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Feature 003 (FR-006, research R7): the share's results from the cache, else Results, as a hearing-resulted event. */
class HearingResultsSourceTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");
    private static final LocalDate NOTIFIED_DAY = LocalDate.parse("2026-05-04");

    private final ResultsClient resultsClient = mock(ResultsClient.class);
    private final ResultsCacheClient cacheClient = mock(ResultsCacheClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResultsCacheClient> cacheProvider = mock(ObjectProvider.class);
    private final HearingResultsSource source = new HearingResultsSource(cacheProvider, resultsClient, JsonMapper.builder().build());

    @Test
    void cache_disabled_should_read_results_and_take_the_day_from_the_notification() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        final HearingResultedEvent event = source.sharedResults(HEARING_ID, NOTIFIED_DAY, false).orElseThrow();

        assertThat(event.hearing().id()).isEqualTo(HEARING_ID);
        assertThat(event.hearingDay()).isEqualTo(NOTIFIED_DAY);
        assertThat(event.sharedTime()).isNotNull();
    }

    @Test
    void results_query_shape_without_reshare_flag_should_take_it_from_the_notification() {
        // the real query returns only {hearing, sharedTime} (research F8): a missing flag must not skip the share
        final String details = "{\"hearing\":" + JsonMapper.builder().build()
                .readTree(Fixtures.json("events/hearing-resulted-enforcement.json")).get("hearing") + "}";
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(details));

        final HearingResultedEvent event = source.sharedResults(HEARING_ID, NOTIFIED_DAY, false).orElseThrow();

        assertThat(event.isReshare()).isFalse();
        assertThat(event.hearingDay()).isEqualTo(NOTIFIED_DAY);
    }

    @Test
    void cached_reshare_flag_should_be_replaced_by_the_notification_s() {
        when(cacheProvider.getIfAvailable()).thenReturn(cacheClient);
        when(cacheClient.sharedResults(HEARING_ID, DAY))
                .thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json").replace("\"isReshare\": false", "\"isReshare\": true")));

        assertThat(source.sharedResults(HEARING_ID, DAY, false).orElseThrow().isReshare()).isFalse();
    }

    // research R22: the flag comes from the notification, whatever the source carries
    @Test
    void reshare_flag_should_come_from_the_notification() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        assertThat(source.sharedResults(HEARING_ID, DAY, true).orElseThrow().isReshare()).isTrue();
        assertThat(source.sharedResults(HEARING_ID, DAY, false).orElseThrow().isReshare()).isFalse();
    }

    @Test
    void cache_hit_should_not_call_results() {
        when(cacheProvider.getIfAvailable()).thenReturn(cacheClient);
        when(cacheClient.sharedResults(HEARING_ID, DAY)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isPresent();
        verifyNoInteractions(resultsClient);
    }

    @Test
    void cache_miss_should_fall_back_to_results() {
        when(cacheProvider.getIfAvailable()).thenReturn(cacheClient);
        when(cacheClient.sharedResults(HEARING_ID, DAY)).thenReturn(Optional.empty());
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isPresent();
    }

    @Test
    void cache_value_not_ready_should_fall_back_to_results() {
        when(cacheProvider.getIfAvailable()).thenReturn(cacheClient);
        when(cacheClient.sharedResults(HEARING_ID, DAY)).thenReturn(Optional.of("{\"hearing\":{\"id\":\"" + HEARING_ID + "\"}}"));
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isPresent();
    }

    @Test
    void unreadable_cache_value_should_fall_back_to_results() {
        when(cacheProvider.getIfAvailable()).thenReturn(cacheClient);
        when(cacheClient.sharedResults(HEARING_ID, DAY)).thenReturn(Optional.of("{\"hearing\": [ \"Edward\""));
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of(Fixtures.json("events/hearing-resulted-enforcement.json")));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isPresent();
    }

    @Test
    void hearing_without_prosecution_cases_should_not_be_ready() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of("{\"hearing\":{\"id\":\"" + HEARING_ID + "\"}}"));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isEmpty();
    }

    @Test
    void hearing_with_no_prosecution_cases_yet_should_not_be_ready() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of("{\"hearing\":{\"id\":\"" + HEARING_ID + "\",\"prosecutionCases\":[]}}"));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isEmpty();
    }

    @Test
    void unknown_hearing_should_not_be_ready() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.empty());

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isEmpty();
    }

    @Test
    void unreadable_results_should_be_unavailable_without_quoting_them() {
        when(resultsClient.hearingDetails(HEARING_ID)).thenReturn(Optional.of("{\"hearing\": [ \"Edward\""));

        assertThatThrownBy(() -> source.sharedResults(HEARING_ID, DAY, false)).isInstanceOf(ResultsUnavailableException.class)
                .hasMessageNotContaining("Edward");
    }
}
