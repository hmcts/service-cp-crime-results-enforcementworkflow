package uk.gov.hmcts.cp.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.config.PaymentTermsProperties;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResultPrompt;
import uk.gov.hmcts.cp.openapi.model.PaymentTerms;
import uk.gov.hmcts.cp.service.ResolvedCodes;
import uk.gov.hmcts.cp.service.ResolvedResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 004 (CIMD-4251) T020, T027, T030: the payment terms block per the ticket's mapping table and scenarios, with
 * the fake prompt references of the test configuration (the real ones are in application.yaml). Synthetic values only.
 */
class PaymentTermsMapperTest {

    private static final String AMOUNT = "test-instalment-amount";
    private static final String FREQUENCY = "test-frequency";
    private static final String LUMP_SUM = "test-lump-sum";
    private static final String START = "test-instalment-start-date";
    private static final String PAY_BY = "test-pay-by-date";
    private static final String CARD = "test-payment-card-required";
    private static final String PARENT = "test-parent-guardian-to-pay";
    private static final LocalDate START_DATE = LocalDate.of(2031, 3, 7);

    private final PaymentTermsMapper mapper = mapper(configured());

    @Test
    void no_payment_terms_result_should_give_none() {
        assertThat(mapper.map(codes(result("SC"), result("MPSO")))).isInstanceOf(PaymentTermsOutcome.None.class);
    }

    // AC1: SC + INSTL weekly 500 -> W, 500, start date, N, N; no lump sum
    @Test
    void instl_should_give_frequency_amount_start_date_and_n_flags() {
        final PaymentTerms block = block(codes(result("SC"), instl("500", "test-weekly")));

        assertThat(block.getInstalmentFrequency()).isEqualTo(PaymentTerms.InstalmentFrequencyEnum.W);
        assertThat(block.getInstalmentAmount()).isEqualByComparingTo("500");
        assertThat(block.getPaymentDueDate()).isEqualTo(START_DATE);
        assertThat(block.getLumpSum()).isNull();
        assertThat(block.getPaymentCardRequested()).isEqualTo(PaymentTerms.PaymentCardRequestedEnum.N);
        assertThat(block.getParentToPay()).isEqualTo(PaymentTerms.ParentToPayEnum.N);
        assertNoEnforcementOverride(block);
    }

    // 3.1
    @Test
    void instl_monthly_should_give_m() {
        assertThat(block(codes(instl("60", "test-monthly"))).getInstalmentFrequency()).isEqualTo(PaymentTerms.InstalmentFrequencyEnum.M);
    }

    // 3.2
    @Test
    void lumsi_should_add_the_lump_sum() {
        final PaymentTerms block = block(codes(result("SC", prompt(LUMP_SUM, "2500"), prompt(AMOUNT, "500"),
                prompt(FREQUENCY, "test-fortnightly"), prompt(START, "2031-03-07")).withCode("LUMSI")));

        assertThat(block.getLumpSum()).isEqualByComparingTo("2500");
        assertThat(block.getInstalmentAmount()).isEqualByComparingTo("500");
        assertThat(block.getInstalmentFrequency()).isEqualTo(PaymentTerms.InstalmentFrequencyEnum.F);
        assertThat(block.getPaymentDueDate()).isEqualTo(START_DATE);
    }

    // AC3: the same block whatever the enforcement result alongside
    @ParameterizedTest
    @ValueSource(strings = {"SC", "MPSO", "PGPAY", "FTTP"})
    void instl_block_should_not_depend_on_the_parent_result(final String parent) {
        assertThat(block(codes(result(parent), instl("500", "test-weekly"))))
                .isEqualTo(block(codes(instl("500", "test-weekly"))));
    }

    @Test
    void instl_without_amount_should_be_incomplete() {
        assertUnsendable(codes(result("INSTL", prompt(FREQUENCY, "test-weekly"), prompt(START, "2031-03-07"))),
                MappingFailureReason.PAYMENT_TERMS_INCOMPLETE, "INSTL instalment-amount missing");
    }

    @Test
    void instl_without_frequency_should_be_incomplete() {
        assertUnsendable(codes(result("INSTL", prompt(AMOUNT, "500"), prompt(START, "2031-03-07"))),
                MappingFailureReason.PAYMENT_TERMS_INCOMPLETE, "INSTL frequency missing");
    }

    @Test
    void instl_with_blank_start_date_should_be_incomplete() {
        assertUnsendable(codes(result("INSTL", prompt(AMOUNT, "500"), prompt(FREQUENCY, "test-weekly"), prompt(START, " "))),
                MappingFailureReason.PAYMENT_TERMS_INCOMPLETE, "INSTL instalment-start-date missing");
    }

    @Test
    void lumsi_without_lump_sum_should_be_incomplete() {
        assertUnsendable(codes(instl("500", "test-weekly").withCode("LUMSI")),
                MappingFailureReason.PAYMENT_TERMS_INCOMPLETE, "LUMSI lump-sum missing");
    }

    @Test
    void unreadable_amount_should_be_unreadable() {
        assertUnsendable(codes(instl("five hundred", "test-weekly")), MappingFailureReason.PAYMENT_TERMS_UNREADABLE,
                "INSTL instalment-amount not readable");
    }

    @Test
    void unknown_frequency_should_be_unreadable() {
        assertUnsendable(codes(instl("500", "daily")), MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "INSTL frequency not readable");
    }

    @Test
    void unreadable_start_date_should_be_unreadable() {
        assertUnsendable(codes(result("INSTL", prompt(AMOUNT, "500"), prompt(FREQUENCY, "test-weekly"), prompt(START, "07/03/2031"))),
                MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "INSTL instalment-start-date not readable");
    }

    @Test
    void references_not_configured_should_be_unreadable() {
        assertUnsendable(mapper(new PaymentTermsProperties()), codes(instl("500", "test-weekly")),
                MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "INSTL instalment-amount not configured");
    }

    @Test
    void two_equal_blocks_should_give_one_block() {
        assertThat(block(codes(instl("500", "test-weekly"), instl("500.00", "TEST-WEEKLY"))).getInstalmentAmount())
                .isEqualByComparingTo("500");
    }

    @Test
    void different_blocks_should_be_conflicting() {
        assertUnsendable(codes(instl("500", "test-weekly"), instl("60", "test-monthly").withCode("LUMSI", prompt(LUMP_SUM, "100"))),
                MappingFailureReason.PAYMENT_TERMS_CONFLICTING, "INSTL, LUMSI");
    }

    @Test
    void payment_terms_code_without_its_result_detail_should_be_unreadable() {
        final ResolvedCodes codesOnly = new ResolvedCodes(List.of("INSTL"), new LinkedHashSet<>(List.of("INSTL")), List.of());

        assertUnsendable(codesOnly, MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "INSTL result detail missing");
    }

    // analysis L2: an optional prompt with no payload field (DID) is ignored
    @Test
    void other_prompts_should_be_ignored() {
        final PaymentTerms block = block(codes(instl("500", "test-weekly").with(prompt("test-did", "anything"))));

        assertThat(block).isEqualTo(block(codes(instl("500", "test-weekly"))));
    }

    @Test
    void failure_detail_should_never_contain_a_value() {
        final List<PaymentTermsOutcome> outcomes = List.of(
                mapper.map(codes(instl("487.13x", "test-weekly"))),
                mapper.map(codes(result("INSTL", prompt(AMOUNT, "487.13"), prompt(FREQUENCY, "test-weekly"), prompt(START, "2031-13-45")))),
                mapper.map(codes(instl("487.13", "test-weekly"), instl("2500.37", "test-weekly"))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(PaymentTermsOutcome.Unsendable.class,
                u -> assertThat(u.detail()).doesNotContain("487", "2500", "2031", "test-weekly")));
    }

    // 3.3, 3.4: PDATE / PTFOR -> due date and flags only
    @ParameterizedTest
    @ValueSource(strings = {"PDATE", "PTFOR"})
    void pay_by_date_results_should_give_the_due_date_only(final String code) {
        final PaymentTerms block = block(codes(result("SC"), result(code, prompt(PAY_BY, "2031-05-16"))));

        assertThat(block.getPaymentDueDate()).isEqualTo(LocalDate.of(2031, 5, 16));
        assertThat(block.getInstalmentFrequency()).isNull();
        assertThat(block.getInstalmentAmount()).isNull();
        assertThat(block.getLumpSum()).isNull();
        assertThat(block.getPaymentCardRequested()).isEqualTo(PaymentTerms.PaymentCardRequestedEnum.N);
        assertThat(block.getParentToPay()).isEqualTo(PaymentTerms.ParentToPayEnum.N);
        assertNoEnforcementOverride(block);
    }

    @Test
    void pdate_without_pay_by_date_should_be_incomplete() {
        assertUnsendable(codes(result("PDATE")), MappingFailureReason.PAYMENT_TERMS_INCOMPLETE, "PDATE pay-by-date missing");
    }

    @Test
    void ptfor_with_unreadable_pay_by_date_should_be_unreadable() {
        assertUnsendable(codes(result("PTFOR", prompt(PAY_BY, "soon"))), MappingFailureReason.PAYMENT_TERMS_UNREADABLE,
                "PTFOR pay-by-date not readable");
    }

    // 3.5: flags absent or no -> N, N
    @Test
    void flags_should_be_n_when_absent_or_no() {
        final PaymentTerms absent = block(codes(instl("500", "test-weekly")));
        final PaymentTerms no = block(codes(instl("500", "test-weekly").with(prompt(CARD, "test-no"), prompt(PARENT, "TEST-NO"))));

        assertThat(List.of(absent.getPaymentCardRequested(), no.getPaymentCardRequested())).containsOnly(PaymentTerms.PaymentCardRequestedEnum.N);
        assertThat(List.of(absent.getParentToPay(), no.getParentToPay())).containsOnly(PaymentTerms.ParentToPayEnum.N);
    }

    // 3.6: PGPAY + INSTL with both prompts yes -> Y, Y
    @Test
    void flags_should_be_y_when_the_prompts_say_yes() {
        final PaymentTerms block = block(codes(result("PGPAY"),
                instl("500", "test-weekly").with(prompt(CARD, "test-yes"), prompt(PARENT, " Test-Yes "))));

        assertThat(block.getPaymentCardRequested()).isEqualTo(PaymentTerms.PaymentCardRequestedEnum.Y);
        assertThat(block.getParentToPay()).isEqualTo(PaymentTerms.ParentToPayEnum.Y);
    }

    @Test
    void flag_with_another_value_should_be_unreadable() {
        assertUnsendable(codes(result("PDATE", prompt(PAY_BY, "2031-05-16"), prompt(CARD, "maybe"))),
                MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "PDATE payment-card-required not readable");
    }

    @Test
    void flag_prompt_not_configured_should_be_unreadable() {
        final PaymentTermsProperties properties = configured();
        properties.getPrompts().setParentGuardianToPay(List.of());

        assertUnsendable(mapper(properties), codes(result("PDATE", prompt(PAY_BY, "2031-05-16"))),
                MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "PDATE parent-guardian-to-pay not configured");
    }

    private PaymentTerms block(final ResolvedCodes codes) {
        final PaymentTermsOutcome outcome = mapper.map(codes);
        assertThat(outcome).isInstanceOf(PaymentTermsOutcome.Block.class);
        return ((PaymentTermsOutcome.Block) outcome).block();
    }

    private void assertUnsendable(final ResolvedCodes codes, final MappingFailureReason reason, final String detail) {
        assertUnsendable(mapper, codes, reason, detail);
    }

    private static void assertUnsendable(final PaymentTermsMapper mapper, final ResolvedCodes codes, final MappingFailureReason reason,
                                         final String detail) {
        assertThat(mapper.map(codes)).isInstanceOfSatisfying(PaymentTermsOutcome.Unsendable.class, u -> {
            assertThat(u.reason()).isEqualTo(reason);
            assertThat(u.detail()).isEqualTo(detail);
        });
    }

    private static void assertNoEnforcementOverride(final PaymentTerms block) {
        assertThat(block.getEnforcementOverride()).isNull();
        assertThat(block.getEnforcementOverrideEnforcer()).isNull();
        assertThat(block.getEnforcementOverrideLjaCode()).isNull();
    }

    private static PaymentTermsMapper mapper(final PaymentTermsProperties properties) {
        return new PaymentTermsMapper(properties, new PaymentValueParser(properties));
    }

    private static PaymentTermsProperties configured() {
        final PaymentTermsProperties properties = new PaymentTermsProperties();
        final PaymentTermsProperties.Prompts prompts = properties.getPrompts();
        prompts.setInstalmentAmount(List.of(AMOUNT));
        prompts.setFrequency(List.of(FREQUENCY));
        prompts.setLumpSum(List.of(LUMP_SUM));
        prompts.setInstalmentStartDate(List.of(START));
        prompts.setPayByDate(List.of(PAY_BY));
        prompts.setPaymentCardRequired(List.of(CARD));
        prompts.setParentGuardianToPay(List.of(PARENT));
        properties.setFrequencyValues(Map.of("W", List.of("test-weekly"), "F", List.of("test-fortnightly"), "M", List.of("test-monthly")));
        properties.setYesValues(List.of("test-yes"));
        properties.setNoValues(List.of("test-no"));
        return properties;
    }

    private static ResolvedCodes codes(final TestResult... results) {
        return ResolvedCodes.fromResults(Arrays.stream(results).map(TestResult::resolved).toList(), List.of());
    }

    private static TestResult instl(final String amount, final String frequency) {
        return result("INSTL", prompt(AMOUNT, amount), prompt(FREQUENCY, frequency), prompt(START, "2031-03-07"));
    }

    private static TestResult result(final String code, final JudicialResultPrompt... prompts) {
        return new TestResult(code, List.of(prompts));
    }

    private static JudicialResultPrompt prompt(final String reference, final String value) {
        return new JudicialResultPrompt(reference, reference, value);
    }

    private record TestResult(String code, List<JudicialResultPrompt> prompts) {
        TestResult withCode(final String newCode, final JudicialResultPrompt... more) {
            return new TestResult(newCode, prompts).with(more);
        }

        TestResult with(final JudicialResultPrompt... more) {
            final List<JudicialResultPrompt> all = new ArrayList<>(prompts);
            all.addAll(List.of(more));
            return new TestResult(code, all);
        }

        ResolvedResult resolved() {
            return new ResolvedResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), code, code, false,
                    LocalDate.of(2026, 5, 3), prompts);
        }
    }

    // review 2026-11-18: references set but no value set to read them with -> "not configured", not "not readable"
    @Test
    void frequency_values_not_configured_should_say_so() {
        final PaymentTermsProperties properties = configured();
        properties.setFrequencyValues(Map.of());

        assertUnsendable(mapper(properties), codes(instl("500", "test-weekly")), MappingFailureReason.PAYMENT_TERMS_UNREADABLE,
                "INSTL frequency not configured");
    }

    @Test
    void yes_no_values_not_configured_should_say_so_only_when_the_flag_prompt_is_present() {
        final PaymentTermsProperties properties = configured();
        properties.setYesValues(List.of());
        properties.setNoValues(List.of());

        assertThat(mapper(properties).map(codes(instl("500", "test-weekly")))).isInstanceOf(PaymentTermsOutcome.Block.class);
        assertUnsendable(mapper(properties), codes(instl("500", "test-weekly").with(prompt(CARD, "test-yes"))),
                MappingFailureReason.PAYMENT_TERMS_UNREADABLE, "INSTL payment-card-required not configured");
    }

    // review 2026-11-18: the same prompt captured twice
    @Test
    void a_prompt_captured_twice_with_different_values_should_be_unreadable() {
        assertUnsendable(codes(instl("500", "test-weekly").with(prompt(AMOUNT, "600"))), MappingFailureReason.PAYMENT_TERMS_UNREADABLE,
                "INSTL instalment-amount captured with different values");
    }

    @Test
    void a_prompt_captured_twice_with_the_same_meaning_should_be_read() {
        final PaymentTerms block = block(codes(instl("500", "test-weekly").with(prompt(AMOUNT, "£500.00"), prompt(FREQUENCY, "TEST-WEEKLY"))));

        assertThat(block.getInstalmentAmount()).isEqualByComparingTo("500");
        assertThat(block.getInstalmentFrequency()).isEqualTo(PaymentTerms.InstalmentFrequencyEnum.W);
    }

    @Test
    void amounts_should_keep_their_value() {
        assertThat(block(codes(instl("£1,500.00", "test-weekly"))).getInstalmentAmount()).isEqualTo(new BigDecimal("1500.00"));
    }
}
