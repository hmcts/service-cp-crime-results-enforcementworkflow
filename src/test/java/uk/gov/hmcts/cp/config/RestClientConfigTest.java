package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    /** The gateway's own total towards APIM: connect 5s + read 40s (research.md R20). */
    private static final long GATEWAY_TOTAL_BUDGET_MS = 45_000;

    private final RestClientConfig config = new RestClientConfig();

    @Test
    void named_builders_should_be_created_with_timeouts() {
        final RestClient.Builder referenceData = config.referenceDataRestClientBuilder(5000, 10000);
        final RestClient.Builder gateway = config.enforcementGatewayRestClientBuilder(5000, 50000);

        assertThat(referenceData).isNotNull();
        assertThat(gateway).isNotNull().isNotSameAs(referenceData);
    }

    @Test
    void configured_timeouts_should_follow_the_r20_budget() throws IOException {
        final PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst();

        assertThat(yaml.getProperty("cp.reference-data.connect-timeout-ms")).isEqualTo(5000);
        assertThat(yaml.getProperty("cp.reference-data.read-timeout-ms")).isEqualTo(10000);
        assertThat(yaml.getProperty("cp.enforcement-gateway.connect-timeout-ms")).isEqualTo(5000);
        final int gatewayReadTimeout = (Integer) yaml.getProperty("cp.enforcement-gateway.read-timeout-ms");
        assertThat(gatewayReadTimeout).isEqualTo(50000);
        // Each hop must give up before its caller, so the caller outlives the gateway's own budget.
        assertThat((long) gatewayReadTimeout).isGreaterThan(GATEWAY_TOTAL_BUDGET_MS);
        // End to end (connect + read) stays under the 60s success criterion SC-006.
        assertThat(5000L + gatewayReadTimeout).isLessThan(60_000L);
    }
}
