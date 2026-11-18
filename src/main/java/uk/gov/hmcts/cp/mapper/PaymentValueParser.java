package uk.gov.hmcts.cp.mapper;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.PaymentTermsProperties;
import uk.gov.hmcts.cp.openapi.model.PaymentTerms.InstalmentFrequencyEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads captured payment terms values (feature 004, research R6). Each method returns the value, or empty when the
 * captured text cannot be interpreted; the caller names the logical field in the failure. Captured values are never
 * logged or echoed (FR-014). Everything configurable is resolved once, at construction.
 * <ul>
 *   <li>Amounts: an optional leading {@code £} and surrounding spaces; digits with optional thousands separators in
 *       their proper places ({@code 1,500.00}); at most two decimal places; not negative; never rounded. A comma
 *       anywhere else ({@code 1,50}) is unreadable rather than guessed.</li>
 *   <li>Dates: the configured {@code date-formats}, in order, strict (no 30 February).</li>
 *   <li>Frequency and yes/no: the configured values, ignoring case and surrounding spaces.</li>
 * </ul>
 */
@Component
public class PaymentValueParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?");
    private static final String POUND = "£";

    private final List<DateTimeFormatter> dateFormatters;
    private final Map<String, InstalmentFrequencyEnum> frequencies = new HashMap<>();
    private final Set<String> yesValues;
    private final Set<String> noValues;

    public PaymentValueParser(final PaymentTermsProperties properties) {
        this.dateFormatters = properties.dateFormatters();
        properties.getFrequencyValues().forEach((gob, captured) -> normalised(captured)
                .forEach(value -> frequencies.put(value, InstalmentFrequencyEnum.fromValue(PaymentTermsProperties.frequencyKey(gob)))));
        this.yesValues = normalised(properties.getYesValues());
        this.noValues = normalised(properties.getNoValues());
    }

    public Optional<BigDecimal> amount(final String captured) {
        Optional<BigDecimal> amount = Optional.empty();
        if (captured != null) {
            String text = captured.strip();
            if (text.startsWith(POUND)) {
                text = text.substring(POUND.length()).strip();
            }
            if (AMOUNT_PATTERN.matcher(text).matches()) {
                amount = Optional.of(new BigDecimal(text.replace(",", "")));
            }
        }
        return amount;
    }

    public Optional<LocalDate> date(final String captured) {
        Optional<LocalDate> date = Optional.empty();
        if (captured != null && !captured.isBlank()) {
            final String text = captured.strip();
            date = dateFormatters.stream().map(format -> parse(text, format)).flatMap(Optional::stream).findFirst();
        }
        return date;
    }

    /** Whether any frequency value is configured: without any, a frequency is "not configured", not unreadable. */
    public boolean hasFrequencyValues() {
        return !frequencies.isEmpty();
    }

    /** Whether any yes or no value is configured. */
    public boolean hasYesNoValues() {
        return !yesValues.isEmpty() || !noValues.isEmpty();
    }

    public Optional<InstalmentFrequencyEnum> frequency(final String captured) {
        return Optional.ofNullable(frequencies.get(PaymentTermsProperties.normalise(captured)));
    }

    /** True for a configured yes value, false for a configured no value, empty for anything else. */
    public Optional<Boolean> yesNo(final String captured) {
        final String value = PaymentTermsProperties.normalise(captured);
        Optional<Boolean> answer = Optional.empty();
        if (yesValues.contains(value)) {
            answer = Optional.of(Boolean.TRUE);
        } else if (noValues.contains(value)) {
            answer = Optional.of(Boolean.FALSE);
        }
        return answer;
    }

    private static Optional<LocalDate> parse(final String text, final DateTimeFormatter format) {
        Optional<LocalDate> date;
        try {
            date = Optional.of(LocalDate.parse(text, format));
        } catch (DateTimeParseException e) {
            // not this format; the captured value is never logged
            date = Optional.empty();
        }
        return date;
    }

    private static Set<String> normalised(final List<String> values) {
        // a blank configured value never matches: normalise() turns a missing captured value into ""
        return values.stream().map(PaymentTermsProperties::normalise).filter(v -> !v.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
