package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 004 T007: {@code cp.hearing-result.payment-terms} (data-model.md §2, contracts/payment-terms-configuration.md). */
class PaymentTermsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_should_be_empty_except_the_iso_date_format() {
        runner.run(ctx -> {
            final PaymentTermsProperties properties = ctx.getBean(PaymentTermsProperties.class);
            final PaymentTermsProperties.Prompts prompts = properties.getPrompts();
            assertThat(prompts.getInstalmentAmount()).isEmpty();
            assertThat(prompts.getFrequency()).isEmpty();
            assertThat(prompts.getLumpSum()).isEmpty();
            assertThat(prompts.getInstalmentStartDate()).isEmpty();
            assertThat(prompts.getPayByDate()).isEmpty();
            assertThat(prompts.getPaymentCardRequired()).isEmpty();
            assertThat(prompts.getParentGuardianToPay()).isEmpty();
            assertThat(properties.getFrequencyValues()).isEmpty();
            assertThat(properties.getYesValues()).isEmpty();
            assertThat(properties.getNoValues()).isEmpty();
            assertThat(properties.getDateFormats()).containsExactly("yyyy-MM-dd");
            assertThat(properties.emptyKeys()).containsExactly("prompts.instalment-amount", "prompts.frequency",
                    "prompts.lump-sum", "prompts.instalment-start-date", "prompts.pay-by-date",
                    "prompts.payment-card-required", "prompts.parent-guardian-to-pay", "frequency-values", "yes-values",
                    "no-values");
        });
    }

    @Test
    void every_key_should_bind_from_configuration() {
        runner.withPropertyValues(
                        "cp.hearing-result.payment-terms.prompts.instalment-amount=test-amount",
                        "cp.hearing-result.payment-terms.prompts.frequency=test-frequency",
                        "cp.hearing-result.payment-terms.prompts.lump-sum=test-lump-sum",
                        "cp.hearing-result.payment-terms.prompts.instalment-start-date=test-start",
                        "cp.hearing-result.payment-terms.prompts.pay-by-date=test-pay-by",
                        "cp.hearing-result.payment-terms.prompts.payment-card-required=test-card",
                        "cp.hearing-result.payment-terms.prompts.parent-guardian-to-pay=test-parent-a,test-parent-b",
                        "cp.hearing-result.payment-terms.frequency-values.W[0]=test-weekly",
                        // a captured value keeps its spaces and dots (a map key would lose them)
                        "cp.hearing-result.payment-terms.frequency-values.W[1]=every wk.",
                        "cp.hearing-result.payment-terms.yes-values=test-yes",
                        "cp.hearing-result.payment-terms.no-values=test-no",
                        "cp.hearing-result.payment-terms.date-formats=dd/MM/yyyy,yyyy-MM-dd")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final PaymentTermsProperties properties = ctx.getBean(PaymentTermsProperties.class);
                    assertThat(properties.getPrompts().getInstalmentAmount()).containsExactly("test-amount");
                    assertThat(properties.getPrompts().getParentGuardianToPay()).containsExactly("test-parent-a", "test-parent-b");
                    assertThat(properties.getFrequencyValues()).containsExactlyEntriesOf(Map.of("W", List.of("test-weekly", "every wk.")));
                    assertThat(properties.getYesValues()).containsExactly("test-yes");
                    assertThat(properties.getNoValues()).containsExactly("test-no");
                    assertThat(properties.getDateFormats()).containsExactly("dd/MM/yyyy", "yyyy-MM-dd");
                    assertThat(properties.emptyKeys()).isEmpty();
                });
    }

    @Test
    void startup_should_fail_for_a_frequency_other_than_w_f_or_m() {
        runner.withPropertyValues("cp.hearing-result.payment-terms.frequency-values.D[0]=test-daily")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    // review 2026-11-18: environment-variable binding lower-cases map keys
    @Test
    void lower_case_frequency_keys_should_be_accepted() {
        runner.withPropertyValues("cp.hearing-result.payment-terms.frequency-values.w[0]=test-weekly")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void startup_should_fail_for_a_captured_value_under_two_frequencies() {
        runner.withPropertyValues(
                        "cp.hearing-result.payment-terms.frequency-values.W[0]=test-same",
                        "cp.hearing-result.payment-terms.frequency-values.M[0]=TEST-SAME")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void date_formatters_should_keep_quoted_text_and_be_strict() {
        final PaymentTermsProperties properties = new PaymentTermsProperties();
        properties.setDateFormats(List.of("'by' d MMM yyyy"));

        assertThat(properties.isDateFormatsValid()).isTrue();
        assertThat(java.time.LocalDate.parse("by 7 Mar 2031", properties.dateFormatters().getFirst()))
                .isEqualTo(java.time.LocalDate.of(2031, 3, 7));
    }

    @Test
    void startup_should_fail_for_a_prompt_reference_under_two_keys() {
        runner.withPropertyValues(
                        "cp.hearing-result.payment-terms.prompts.instalment-amount=test-same",
                        "cp.hearing-result.payment-terms.prompts.lump-sum=test-same")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startup_should_fail_for_a_value_in_both_yes_and_no_values() {
        runner.withPropertyValues(
                        "cp.hearing-result.payment-terms.yes-values=test-yes",
                        "cp.hearing-result.payment-terms.no-values=TEST-YES")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startup_should_fail_for_an_invalid_date_format() {
        runner.withPropertyValues("cp.hearing-result.payment-terms.date-formats=yyyy-MM-dd#")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(PaymentTermsProperties.class)
    static class TestConfig {
    }
}
