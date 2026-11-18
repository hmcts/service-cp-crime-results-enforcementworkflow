package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code cp.hearing-result.payment-terms}: how to read a payment terms result's prompts (feature 004, CIMD-4251;
 * data-model.md §2, research R3). The CP prompt references and value formats are set in {@code application.yaml}
 * (spec OQ-1, resolved from CP reference data). Every list defaults to empty here; a key left empty means a payment
 * terms result cannot be read and the share is recorded as unsendable, never sent with invented values (constitution
 * IX).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.hearing-result.payment-terms")
public class PaymentTermsProperties {

    private static final Set<String> FREQUENCIES = Set.of("W", "F", "M");

    /** Prompt references to read, per logical value (matched exactly against {@code promptReference}). */
    private Prompts prompts = new Prompts();

    /**
     * GOB frequency ({@code W}, {@code F} or {@code M}) → the captured values that mean it, matched ignoring case and
     * surrounding spaces. Keyed by the GOB code, not the captured value: Spring drops characters such as spaces from map
     * keys, so a captured value is only kept exactly as a list element.
     */
    private Map<String, List<String>> frequencyValues = new LinkedHashMap<>();

    /** Captured values meaning yes for the payment card and parent/guardian prompts (ignoring case). */
    private List<String> yesValues = new ArrayList<>();

    /** Captured values meaning no for the payment card and parent/guardian prompts (ignoring case). */
    private List<String> noValues = new ArrayList<>();

    /** Date patterns tried in order. */
    private List<String> dateFormats = new ArrayList<>(List.of("yyyy-MM-dd"));

    @Getter
    @Setter
    public static class Prompts {
        private List<String> instalmentAmount = new ArrayList<>();
        private List<String> frequency = new ArrayList<>();
        private List<String> lumpSum = new ArrayList<>();
        private List<String> instalmentStartDate = new ArrayList<>();
        private List<String> payByDate = new ArrayList<>();
        private List<String> paymentCardRequired = new ArrayList<>();
        private List<String> parentGuardianToPay = new ArrayList<>();

        private Map<String, List<String>> byKey() {
            final Map<String, List<String>> keys = new LinkedHashMap<>();
            keys.put("prompts.instalment-amount", instalmentAmount);
            keys.put("prompts.frequency", frequency);
            keys.put("prompts.lump-sum", lumpSum);
            keys.put("prompts.instalment-start-date", instalmentStartDate);
            keys.put("prompts.pay-by-date", payByDate);
            keys.put("prompts.payment-card-required", paymentCardRequired);
            keys.put("prompts.parent-guardian-to-pay", parentGuardianToPay);
            return keys;
        }
    }

    /** The keys still empty (research R9: named in the startup warning; values are never logged). */
    public List<String> emptyKeys() {
        final List<String> empty = new ArrayList<>();
        prompts.byKey().forEach((key, references) -> {
            if (references.isEmpty()) {
                empty.add(key);
            }
        });
        if (frequencyValues.isEmpty()) {
            empty.add("frequency-values");
        }
        if (yesValues.isEmpty()) {
            empty.add("yes-values");
        }
        if (noValues.isEmpty()) {
            empty.add("no-values");
        }
        return empty;
    }

    @AssertTrue(message = "cp.hearing-result.payment-terms.frequency-values keys must be W, F or M")
    public boolean isFrequencyTargetsValid() {
        return frequencyValues.keySet().stream().map(PaymentTermsProperties::frequencyKey).allMatch(FREQUENCIES::contains);
    }

    @AssertTrue(message = "cp.hearing-result.payment-terms.frequency-values: a captured value may mean one frequency only")
    public boolean isFrequencyValuesDistinct() {
        return isDistinct(frequencyValues.values().stream().flatMap(List::stream).map(PaymentTermsProperties::normalise).toList());
    }

    @AssertTrue(message = "cp.hearing-result.payment-terms.prompts: a prompt reference may appear under one key only")
    public boolean isPromptReferencesDistinct() {
        return isDistinct(prompts.byKey().values().stream().flatMap(List::stream).toList());
    }

    @AssertTrue(message = "cp.hearing-result.payment-terms: yes-values and no-values must not overlap")
    public boolean isYesNoDistinct() {
        final Set<String> yes = yesValues.stream().map(PaymentTermsProperties::normalise).collect(Collectors.toSet());
        return noValues.stream().map(PaymentTermsProperties::normalise).noneMatch(yes::contains);
    }

    @AssertTrue(message = "cp.hearing-result.payment-terms.date-formats must be valid date patterns")
    public boolean isDateFormatsValid() {
        return !dateFormats.isEmpty() && dateFormats.stream().allMatch(PaymentTermsProperties::isPattern);
    }

    /**
     * The {@code date-formats} as strict formatters, in order. Strict rejects dates such as 30 February; the era default
     * lets {@code yyyy} (year of era) resolve under strict parsing without touching the pattern text. Month and day names
     * are English and case-insensitive ({@code Sep}, {@code SEP}): en_GB data spells September "Sept".
     */
    public List<DateTimeFormatter> dateFormatters() {
        return dateFormats.stream().map(PaymentTermsProperties::formatter).toList();
    }

    private static DateTimeFormatter formatter(final String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .parseDefaulting(ChronoField.ERA, 1)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /** A {@code frequency-values} key as GOB's code: environment-variable binding lower-cases map keys ({@code w}). */
    public static String frequencyKey(final String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isDistinct(final List<String> values) {
        return new HashSet<>(values).size() == values.size();
    }

    /** Lower case, trimmed: how captured values are compared. */
    public static String normalise(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isPattern(final String pattern) {
        boolean valid = pattern != null && !pattern.isBlank();
        if (valid) {
            try {
                formatter(pattern);
            } catch (IllegalArgumentException e) {
                valid = false;
            }
        }
        return valid;
    }
}
