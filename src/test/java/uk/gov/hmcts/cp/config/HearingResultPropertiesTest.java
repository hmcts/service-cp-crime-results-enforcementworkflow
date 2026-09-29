package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class HearingResultPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_should_be_safe() {
        runner.run(ctx -> {
            final HearingResultProperties properties = ctx.getBean(HearingResultProperties.class);
            assertThat(properties.getPaymentDueDateFallback()).isEqualTo(PaymentDueDateFallback.NONE);
            assertThat(properties.getResultCodeRenames()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("TFOUT", "TFOOUT", "WC", "DW", "WWDN", "WDN"));
            assertThat(properties.getNowsDataItemsByShortCode()).isEmpty();
            assertThat(properties.getStaleSendingThreshold()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.getStaleSendingSweepInterval()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void properties_should_bind_from_configuration() {
        runner.withPropertyValues(
                        "cp.hearing-result.payment-due-date-fallback=HEARING_DATE",
                        "cp.hearing-result.nows-data-items-by-short-code.SC[0]=Account Balance",
                        "cp.hearing-result.stale-sending-threshold=PT10M")
                .run(ctx -> {
                    final HearingResultProperties properties = ctx.getBean(HearingResultProperties.class);
                    assertThat(properties.getPaymentDueDateFallback()).isEqualTo(PaymentDueDateFallback.HEARING_DATE);
                    assertThat(properties.getNowsDataItemsByShortCode()).containsEntry("SC", List.of("Account Balance"));
                    assertThat(properties.getStaleSendingThreshold()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    void startup_should_warn_when_hearing_date_placeholder_enabled(final CapturedOutput output) {
        runner.withPropertyValues("cp.hearing-result.payment-due-date-fallback=HEARING_DATE")
                .run(ctx -> ctx.getBean(HearingResultStartupChecks.class).warnIfPlaceholdersEnabled());

        assertThat(output).contains(HearingResultStartupChecks.PLACEHOLDER_WARNING);
    }

    @Test
    void startup_should_not_warn_by_default(final CapturedOutput output) {
        runner.run(ctx -> ctx.getBean(HearingResultStartupChecks.class).warnIfPlaceholdersEnabled());

        assertThat(output).doesNotContain(HearingResultStartupChecks.PLACEHOLDER_WARNING);
    }

    @Configuration
    @EnableConfigurationProperties(HearingResultProperties.class)
    @Import(HearingResultStartupChecks.class)
    static class TestConfig {
    }
}
