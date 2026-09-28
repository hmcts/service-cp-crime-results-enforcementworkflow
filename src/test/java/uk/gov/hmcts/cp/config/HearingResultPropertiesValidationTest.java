package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HearingResultPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void unknown_nows_data_item_name_should_fail_startup() {
        runner.withPropertyValues("cp.hearing-result.nows-data-items-by-short-code.SC[0]=Not A Real Item")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void gob_nows_data_item_names_should_load() {
        runner.withPropertyValues(
                        "cp.hearing-result.nows-data-items-by-short-code.SC[0]=Account Balance",
                        "cp.hearing-result.nows-data-items-by-short-code.SC[1]=CT Account Bank Details")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties(HearingResultProperties.class)
    static class TestConfig {
    }
}
