package uk.gov.hmcts.cp.mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruncateTest {

    @Test
    void values_within_the_limit_should_be_unchanged() {
        assertThat(Truncate.toMaxLength("Harrison", 8)).isEqualTo("Harrison");
        assertThat(Truncate.toMaxLength(null, 8)).isNull();
    }

    @Test
    void longer_values_should_be_cut_to_the_limit() {
        assertThat(Truncate.toMaxLength("Harrisonson", 8)).isEqualTo("Harrison");
    }

    // maxLength counts characters (code points): a supplementary character is never split in half
    @Test
    void a_supplementary_character_should_count_once_and_never_be_split() {
        final String name = "Ab𝒞cd"; // "Ab𝒞cd": 5 characters, 6 UTF-16 units

        assertThat(Truncate.toMaxLength(name, 5)).isEqualTo(name);
        assertThat(Truncate.toMaxLength(name, 3)).isEqualTo("Ab𝒞");
    }
}
