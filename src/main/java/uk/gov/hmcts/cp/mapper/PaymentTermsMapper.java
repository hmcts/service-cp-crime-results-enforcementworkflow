package uk.gov.hmcts.cp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.PaymentTermsProperties;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResultPrompt;
import uk.gov.hmcts.cp.openapi.model.PaymentTerms;
import uk.gov.hmcts.cp.service.ResolvedCodes;
import uk.gov.hmcts.cp.service.ResolvedResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the {@code paymentTerms} block from a submission's payment terms results (feature 004, CIMD-4251 mapping
 * table; research R4-R7, data-model.md §3-§5).
 * <ul>
 *   <li>INSTL: frequency ({@code instalmentFrequency}, Libra v0.8.0), instalment amount and start date (sent as
 *       {@code paymentDueDate}); LUMSI adds the lump sum.</li>
 *   <li>PDATE, PTFOR: the pay-by date as {@code paymentDueDate}.</li>
 *   <li>Payment card and parent/guardian flags: {@code Y} only when the prompt says yes, {@code N} when absent.</li>
 *   <li>A missing value is {@code PAYMENT_TERMS_INCOMPLETE}; a value that cannot be read, or a prompt whose references are
 *       not configured, is {@code PAYMENT_TERMS_UNREADABLE}; several results with different blocks are
 *       {@code PAYMENT_TERMS_CONFLICTING}. Nothing is guessed (constitution IX).</li>
 *   <li>Enforcement override fields are never set (FR-012). Failure details name the result code and field, never a
 *       value (FR-014).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PaymentTermsMapper {

    /** The CIMD-4251 payment terms results: a business rule, not configuration. */
    private static final Set<String> PAYMENT_TERMS_CODES = Set.of("INSTL", "LUMSI", "PDATE", "PTFOR");
    private static final Set<String> INSTALMENT_CODES = Set.of("INSTL", "LUMSI");
    private static final String LUMSI = "LUMSI";

    private static final String INSTALMENT_AMOUNT = "instalment-amount";
    private static final String FREQUENCY = "frequency";
    private static final String LUMP_SUM = "lump-sum";
    private static final String INSTALMENT_START_DATE = "instalment-start-date";
    private static final String PAY_BY_DATE = "pay-by-date";
    private static final String PAYMENT_CARD_REQUIRED = "payment-card-required";
    private static final String PARENT_GUARDIAN_TO_PAY = "parent-guardian-to-pay";

    private final PaymentTermsProperties properties;
    private final PaymentValueParser parser;

    public PaymentTermsOutcome map(final ResolvedCodes codes) {
        final List<ResolvedResult> results = codes.results().stream()
                .filter(r -> PAYMENT_TERMS_CODES.contains(r.cpShortCode()))
                .toList();
        final Optional<String> withoutDetail = codes.cpShortCodes().stream()
                .filter(PAYMENT_TERMS_CODES::contains)
                .filter(code -> results.stream().noneMatch(r -> code.equals(r.cpShortCode())))
                .findFirst();
        PaymentTermsOutcome outcome = new PaymentTermsOutcome.None();
        if (withoutDetail.isPresent()) {
            // never send a payment terms result without its block
            outcome = unreadable(withoutDetail.get() + " result detail missing");
        } else if (!results.isEmpty()) {
            outcome = combine(results);
        }
        return outcome;
    }

    private PaymentTermsOutcome combine(final List<ResolvedResult> results) {
        final List<PaymentTermsOutcome> outcomes = results.stream().map(result -> new Reader(result).block()).toList();
        final Optional<PaymentTermsOutcome> unsendable = outcomes.stream()
                .filter(PaymentTermsOutcome.Unsendable.class::isInstance)
                .findFirst();
        final List<PaymentTerms> blocks = outcomes.stream()
                .filter(PaymentTermsOutcome.Block.class::isInstance)
                .map(outcome -> ((PaymentTermsOutcome.Block) outcome).block())
                .toList();
        final PaymentTermsOutcome outcome;
        if (unsendable.isPresent()) {
            outcome = unsendable.get();
        } else if (blocks.stream().allMatch(block -> sameArrangement(blocks.getFirst(), block))) {
            outcome = new PaymentTermsOutcome.Block(blocks.getFirst());
        } else {
            outcome = new PaymentTermsOutcome.Unsendable(MappingFailureReason.PAYMENT_TERMS_CONFLICTING,
                    results.stream().map(ResolvedResult::cpShortCode).distinct().collect(Collectors.joining(", ")));
        }
        return outcome;
    }

    /** Equal blocks collapse (R7); amounts compared by value, so 500 and 500.00 are the same arrangement. */
    private static boolean sameArrangement(final PaymentTerms a, final PaymentTerms b) {
        return Objects.equals(a.getInstalmentFrequency(), b.getInstalmentFrequency())
                && Objects.equals(a.getPaymentDueDate(), b.getPaymentDueDate())
                && sameAmount(a.getInstalmentAmount(), b.getInstalmentAmount())
                && sameAmount(a.getLumpSum(), b.getLumpSum())
                && Objects.equals(a.getPaymentCardRequested(), b.getPaymentCardRequested())
                && Objects.equals(a.getParentToPay(), b.getParentToPay());
    }

    /** A value as compared for equality: amounts by numeric value, so 500 and 500.00 are the same. */
    private static Object comparable(final Object value) {
        return value instanceof BigDecimal amount ? amount.stripTrailingZeros() : value;
    }

    private static boolean sameAmount(final BigDecimal a, final BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static PaymentTermsOutcome.Unsendable unreadable(final String detail) {
        return new PaymentTermsOutcome.Unsendable(MappingFailureReason.PAYMENT_TERMS_UNREADABLE, detail);
    }

    /** A value read from one result's prompts, or the reason it can't be. */
    private record Read<T>(T value, PaymentTermsOutcome.Unsendable failure) {
    }

    /** A field this result does not send (for example the lump sum of INSTL). */
    private static <T> Read<T> none() {
        return new Read<>(null, null);
    }

    /** Reads one payment terms result. */
    private final class Reader {

        private final ResolvedResult result;
        private final String code;

        private Reader(final ResolvedResult result) {
            this.result = result;
            this.code = result.cpShortCode();
        }

        private PaymentTermsOutcome block() {
            final PaymentTermsProperties.Prompts prompts = properties.getPrompts();
            final boolean instalments = INSTALMENT_CODES.contains(code);
            final Read<BigDecimal> amount = instalments ? required(INSTALMENT_AMOUNT, prompts.getInstalmentAmount(), parser::amount) : none();
            final Read<PaymentTerms.InstalmentFrequencyEnum> frequency = instalments
                    ? required(FREQUENCY, prompts.getFrequency(), parser.hasFrequencyValues(), parser::frequency) : none();
            final Read<BigDecimal> lumpSum = LUMSI.equals(code) ? required(LUMP_SUM, prompts.getLumpSum(), parser::amount) : none();
            final Read<LocalDate> dueDate = instalments
                    ? required(INSTALMENT_START_DATE, prompts.getInstalmentStartDate(), parser::date)
                    : required(PAY_BY_DATE, prompts.getPayByDate(), parser::date);
            final Read<Boolean> card = flag(PAYMENT_CARD_REQUIRED, prompts.getPaymentCardRequired());
            final Read<Boolean> parent = flag(PARENT_GUARDIAN_TO_PAY, prompts.getParentGuardianToPay());
            // reported in the order of the mapping table, so the detail names the first problem
            final Optional<PaymentTermsOutcome.Unsendable> failure = Stream.of(amount, frequency, dueDate, lumpSum, card, parent)
                    .map(Read::failure)
                    .filter(Objects::nonNull)
                    .findFirst();
            return failure.isPresent() ? failure.get() : new PaymentTermsOutcome.Block(new PaymentTerms()
                    .instalmentFrequency(frequency.value())
                    .instalmentAmount(amount.value())
                    .lumpSum(lumpSum.value())
                    .paymentDueDate(dueDate.value())
                    .paymentCardRequested(card.value() ? PaymentTerms.PaymentCardRequestedEnum.Y : PaymentTerms.PaymentCardRequestedEnum.N)
                    .parentToPay(parent.value() ? PaymentTerms.ParentToPayEnum.Y : PaymentTerms.ParentToPayEnum.N));
        }

        private <T> Read<T> required(final String field, final List<String> references, final Function<String, Optional<T>> parse) {
            return required(field, references, true, parse);
        }

        /**
         * Reads a mandatory value: not configured (no references, or no value set to read it with) is unreadable, an
         * absent prompt is incomplete, a value that cannot be read (or two different values) is unreadable.
         */
        private <T> Read<T> required(final String field, final List<String> references, final boolean valuesConfigured,
                                     final Function<String, Optional<T>> parse) {
            final Read<T> read;
            if (references.isEmpty() || !valuesConfigured) {
                read = notConfigured(field);
            } else {
                final List<String> captured = captured(references);
                if (captured.isEmpty()) {
                    read = new Read<>(null, new PaymentTermsOutcome.Unsendable(MappingFailureReason.PAYMENT_TERMS_INCOMPLETE,
                            code + " " + field + " missing"));
                } else {
                    read = readOne(field, captured, parse);
                }
            }
            return read;
        }

        /** Absent → no (false); present → a configured yes or no value, anything else is unreadable. */
        private Read<Boolean> flag(final String field, final List<String> references) {
            final Read<Boolean> read;
            final List<String> captured = references.isEmpty() ? List.of() : captured(references);
            if (references.isEmpty()) {
                read = notConfigured(field);
            } else if (captured.isEmpty()) {
                read = new Read<>(Boolean.FALSE, null);
            } else if (parser.hasYesNoValues()) {
                read = readOne(field, captured, parser::yesNo);
            } else {
                read = notConfigured(field);
            }
            return read;
        }

        /**
         * One value from the captured texts: every text must be readable, and if the prompt was captured more than once
         * the readings must agree (500 and 500.00, or "Weekly" and "weekly", agree); two different values are never
         * chosen between.
         */
        private <T> Read<T> readOne(final String field, final List<String> captured, final Function<String, Optional<T>> parse) {
            final List<Optional<T>> parsed = captured.stream().map(parse).toList();
            final Read<T> read;
            if (parsed.stream().anyMatch(Optional::isEmpty)) {
                read = unreadableRead(field + " not readable");
            } else if (parsed.stream().map(Optional::get).map(PaymentTermsMapper::comparable).distinct().skip(1).findAny().isPresent()) {
                read = unreadableRead(field + " captured with different values");
            } else {
                read = new Read<>(parsed.getFirst().get(), null);
            }
            return read;
        }

        private <T> Read<T> notConfigured(final String field) {
            return unreadableRead(field + " not configured");
        }

        private <T> Read<T> unreadableRead(final String what) {
            return new Read<>(null, unreadable(code + " " + what));
        }

        /** The distinct non-blank values of the prompts with one of the configured references (stripped). */
        private List<String> captured(final List<String> references) {
            return result.prompts().stream()
                    .filter(p -> references.contains(p.promptReference()))
                    .map(JudicialResultPrompt::value)
                    .filter(v -> v != null && !v.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList();
        }
    }
}
