package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 003 (research R2, R10): the notification allow-list and retry/replay durations. */
class NotificationPropertiesTest {

    private final NotificationProperties properties = new NotificationProperties();

    @Test
    void defaults_should_be_three_retries_and_five_replays_and_a_closed_endpoint() {
        assertThat(properties.getRetryDurations()).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(300));
        assertThat(properties.getReplaySchedule()).hasSize(5);
        assertThat(properties.isAllowed("5f1a6c2e-0000-4000-8000-00000000fa01")).isFalse();
        assertThat(properties.isDurationsPositive()).isTrue();
    }

    @Test
    void allow_list_should_match_ignoring_case_and_reject_null() {
        properties.setAllowedSystemUsers(new ArrayList<>(List.of("5F1A6C2E-0000-4000-8000-00000000FA01")));

        assertThat(properties.isAllowed("5f1a6c2e-0000-4000-8000-00000000fa01")).isTrue();
        assertThat(properties.isAllowed("6f1a6c2e-0000-4000-8000-00000000fa01")).isFalse();
        assertThat(properties.isAllowed(null)).isFalse();
    }

    @Test
    void positive_durations_should_be_valid() {
        properties.setRetryDurations(new ArrayList<>(List.of(Duration.ofSeconds(1))));
        properties.setReplaySchedule(new ArrayList<>(List.of(Duration.ofMinutes(1))));

        assertThat(properties.isDurationsPositive()).isTrue();
    }

    @Test
    void zero_or_negative_durations_should_be_invalid() {
        properties.setRetryDurations(new ArrayList<>(List.of(Duration.ZERO)));
        assertThat(properties.isDurationsPositive()).isFalse();

        properties.setRetryDurations(new ArrayList<>(List.of(Duration.ofSeconds(1))));
        properties.setReplaySchedule(new ArrayList<>(List.of(Duration.ofSeconds(-1))));
        assertThat(properties.isDurationsPositive()).isFalse();
    }
}
