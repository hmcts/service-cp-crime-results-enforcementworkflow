package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 004 T018 (research R9): one warning naming the empty payment terms keys, never a value. */
@ExtendWith(OutputCaptureExtension.class)
class HearingResultStartupChecksTest {

    @Test
    void startup_should_warn_naming_the_empty_keys(final CapturedOutput output) {
        final PaymentTermsProperties properties = new PaymentTermsProperties();
        properties.getPrompts().setInstalmentAmount(List.of("test-secret-reference"));

        new HearingResultStartupChecks(properties).warnIfPaymentTermsUnconfigured();

        assertThat(output).contains(HearingResultStartupChecks.PAYMENT_TERMS_WARNING)
                .contains("prompts.frequency", "frequency-values", "yes-values")
                .doesNotContain("prompts.instalment-amount", "test-secret-reference");
    }

    @Test
    void startup_should_not_warn_when_everything_is_configured(final CapturedOutput output) {
        final PaymentTermsProperties properties = new PaymentTermsProperties();
        final PaymentTermsProperties.Prompts prompts = properties.getPrompts();
        prompts.setInstalmentAmount(List.of("test-a"));
        prompts.setFrequency(List.of("test-b"));
        prompts.setLumpSum(List.of("test-c"));
        prompts.setInstalmentStartDate(List.of("test-d"));
        prompts.setPayByDate(List.of("test-e"));
        prompts.setPaymentCardRequired(List.of("test-f"));
        prompts.setParentGuardianToPay(List.of("test-g"));
        properties.setFrequencyValues(Map.of("W", List.of("test-weekly")));
        properties.setYesValues(List.of("test-yes"));
        properties.setNoValues(List.of("test-no"));

        new HearingResultStartupChecks(properties).warnIfPaymentTermsUnconfigured();

        assertThat(output).doesNotContain(HearingResultStartupChecks.PAYMENT_TERMS_WARNING);
    }
}
