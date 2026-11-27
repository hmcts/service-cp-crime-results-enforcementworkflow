package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DivisionCodeTest {

    private final ResultNotificationProperties properties = new ResultNotificationProperties();

    @Test
    void should_extract_prefix_and_resolve_enforcement() {
        final DivisionCode code = DivisionCode.from("0100104567C");

        assertThat(code.value()).isEqualTo("010");
        assertThat(code.areaFor(properties)).isEqualTo(Area.ENFORCEMENT);
    }

    @Test
    void should_parse_as_integer_not_string_so_a_2_digit_confiscation_code_matches() {
        // the Confiscation set mixes 2- and 3-digit values (045 -> 45): string comparison would miss this
        final DivisionCode code = DivisionCode.from("0450099999A");

        assertThat(code.value()).isEqualTo("045");
        assertThat(code.areaFor(properties)).isEqualTo(Area.CONFISCATION);
    }

    @Test
    void should_resolve_a_3_digit_confiscation_code() {
        assertThat(DivisionCode.from("1160099999A").areaFor(properties)).isEqualTo(Area.CONFISCATION);
    }

    @Test
    void should_reject_a_reference_number_shorter_than_the_prefix() {
        assertThatThrownBy(() -> DivisionCode.from("01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_a_non_numeric_prefix() {
        assertThatThrownBy(() -> DivisionCode.from("ABC0099999A")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_a_null_reference_number() {
        assertThatThrownBy(() -> DivisionCode.from(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
