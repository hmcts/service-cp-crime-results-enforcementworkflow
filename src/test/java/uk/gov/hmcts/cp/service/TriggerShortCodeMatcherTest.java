package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerShortCodeMatcherTest {

    private final TriggerShortCodeMatcher matcher = new TriggerShortCodeMatcher(new ResultNotificationProperties());

    @Test
    void should_match_when_a_trigger_short_code_is_present_for_enforcement() {
        final List<ResolvedResult> results = List.of(result("ADJNN"), result("CW"));

        assertThat(matcher.matches(results, Area.ENFORCEMENT)).isTrue();
    }

    @Test
    void should_not_match_when_only_non_trigger_codes_are_present() {
        final List<ResolvedResult> results = List.of(result("ADJNN"), result("PTNV"));

        assertThat(matcher.matches(results, Area.ENFORCEMENT)).isFalse();
    }

    @Test
    void should_match_the_confiscation_list_for_confiscation_area() {
        assertThat(matcher.matches(List.of(result("BPOC")), Area.CONFISCATION)).isTrue();
    }

    @Test
    void enforcement_code_should_not_match_in_confiscation_area() {
        assertThat(matcher.matches(List.of(result("CW")), Area.CONFISCATION)).isFalse();
    }

    @Test
    void should_not_match_an_empty_result_list() {
        assertThat(matcher.matches(List.of(), Area.ENFORCEMENT)).isFalse();
    }

    private static ResolvedResult result(final String shortCode) {
        return new ResolvedResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), shortCode, shortCode, false,
                LocalDate.parse("2026-05-03"));
    }
}
