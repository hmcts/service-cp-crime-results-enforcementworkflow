package uk.gov.hmcts.cp.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.config.PaymentTermsProperties;
import uk.gov.hmcts.cp.openapi.model.PaymentTerms.InstalmentFrequencyEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 004 T009 (research R6): amounts, dates, frequency and yes/no; synthetic values only. */
class PaymentValueParserTest {

    private final PaymentValueParser parser = new PaymentValueParser(properties(List.of("yyyy-MM-dd")));

    @ParameterizedTest
    @CsvSource({"500,500", "500.00,500.00", "£60,60", "'£1,500.00',1500.00", "' 12.5 ',12.5", "'12,345.67',12345.67",
        "'£ 1,000,000',1000000"})
    void amounts_should_be_read_without_rounding(final String captured, final String expected) {
        assertThat(parser.amount(captured)).contains(new BigDecimal(expected));
    }

    @Test
    void amount_scale_should_be_kept() {
        assertThat(parser.amount("500.00").orElseThrow().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    // a comma that is not a thousands separator (1,50 may be a decimal comma) is unreadable, never guessed
    @ValueSource(strings = {"abc", "-5", "1.234", " ", "£", "1,2,3x", "1e3", "1,50", "1,5000", ",500", "500,", "1.5,00"})
    void unreadable_amounts_should_be_empty(final String captured) {
        assertThat(parser.amount(captured)).isEmpty();
    }

    // review 2026-11-18: en_GB data spells September "Sept"; month names are read as English, any case
    @ParameterizedTest
    @CsvSource({"'16 Sep 2031',2031-09-16", "'16 SEP 2031',2031-09-16", "'16 May 2031',2031-05-16", "'16 may 2031',2031-05-16"})
    void month_names_should_be_english_and_case_insensitive(final String captured, final LocalDate expected) {
        assertThat(new PaymentValueParser(properties(List.of("d MMM yyyy"))).date(captured)).contains(expected);
    }

    @Test
    void quoted_text_in_a_date_format_should_be_kept() {
        assertThat(new PaymentValueParser(properties(List.of("'by' d MMM yyyy"))).date("by 16 May 2031"))
                .contains(LocalDate.of(2031, 5, 16));
    }

    @Test
    void blank_configured_values_should_never_match_a_missing_value() {
        final PaymentTermsProperties properties = properties(List.of("yyyy-MM-dd"));
        properties.setYesValues(List.of("test-yes", " "));
        properties.setFrequencyValues(Map.of("W", List.of("")));

        final PaymentValueParser blanks = new PaymentValueParser(properties);

        assertThat(blanks.yesNo(null)).isEmpty();
        assertThat(blanks.frequency(" ")).isEmpty();
    }

    @Test
    void dates_should_be_read_with_the_configured_formats_in_order() {
        final PaymentValueParser twoFormats = new PaymentValueParser(properties(List.of("dd/MM/yyyy", "yyyy-MM-dd")));

        assertThat(parser.date("2031-03-07")).contains(LocalDate.of(2031, 3, 7));
        assertThat(twoFormats.date("07/03/2031")).contains(LocalDate.of(2031, 3, 7));
        assertThat(twoFormats.date("2031-03-07")).contains(LocalDate.of(2031, 3, 7));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"07/03/2031", "2031-02-30", "next week", "2031-3-7"})
    void unreadable_dates_should_be_empty(final String captured) {
        assertThat(parser.date(captured)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"test-weekly,W", "' TEST-Weekly ',W", "test-fortnightly,F", "test-monthly,M"})
    void frequency_should_be_read_from_configured_values_ignoring_case_and_spaces(final String captured, final String expected) {
        assertThat(parser.frequency(captured)).contains(InstalmentFrequencyEnum.fromValue(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"weekly", "W", "test-daily"})
    void unknown_frequency_should_be_empty(final String captured) {
        assertThat(parser.frequency(captured)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"test-yes,true", "' Test-Yes ',true", "test-no,false", "TEST-NO,false"})
    void yes_no_should_be_read_from_configured_values_ignoring_case(final String captured, final boolean expected) {
        assertThat(parser.yesNo(captured)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yes", "Y", "maybe"})
    void other_yes_no_values_should_be_empty(final String captured) {
        assertThat(parser.yesNo(captured)).isEmpty();
    }

    private static PaymentTermsProperties properties(final List<String> dateFormats) {
        final PaymentTermsProperties properties = new PaymentTermsProperties();
        properties.setFrequencyValues(Map.of("W", List.of("test-weekly"), "F", List.of("test-fortnightly"), "M", List.of("test-monthly")));
        properties.setYesValues(List.of("test-yes"));
        properties.setNoValues(List.of("test-no"));
        properties.setDateFormats(dateFormats);
        return properties;
    }
}
