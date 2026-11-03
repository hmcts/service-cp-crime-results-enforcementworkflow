package uk.gov.hmcts.cp.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTest {

    /** The gateway's own total towards APIM: connect 5s + read 40s (research.md R20). */
    private static final long GATEWAY_TOTAL_BUDGET_MS = 45_000;

    private final RestClientConfig config = new RestClientConfig();

    @Test
    void named_builders_should_apply_their_read_timeout() {
        final WireMockServer slow = new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
        slow.start();
        try {
            slow.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

            final ClientProperties clients = new ClientProperties();
            clients.getReferenceData().setReadTimeout(Duration.ofMillis(300));
            clients.getEnforcementGateway().setReadTimeout(Duration.ofMillis(300));
            final ResultsProperties results = new ResultsProperties();
            results.getResults().setReadTimeout(Duration.ofMillis(300));
            for (final RestClient.Builder builder : List.of(config.referenceDataRestClientBuilder(clients),
                    config.enforcementGatewayRestClientBuilder(clients), config.resultsRestClientBuilder(results))) {
                final long start = System.nanoTime();
                assertThatThrownBy(() -> builder.baseUrl(slow.baseUrl()).build().get().uri("/slow").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
            }
        } finally {
            slow.stop();
        }
    }

    @Test
    void configured_timeouts_should_follow_the_r20_budget() throws IOException {
        final PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst();

        assertThat(duration(yaml, "cp.reference-data.connect-timeout")).isEqualTo(Duration.ofSeconds(5));
        assertThat(duration(yaml, "cp.reference-data.read-timeout")).isEqualTo(Duration.ofSeconds(10));
        assertThat(duration(yaml, "cp.results.connect-timeout")).isEqualTo(Duration.ofSeconds(5));
        assertThat(duration(yaml, "cp.results.read-timeout")).isEqualTo(Duration.ofSeconds(10));
        final Duration gatewayConnectTimeout = duration(yaml, "cp.enforcement-gateway.connect-timeout");
        final Duration gatewayReadTimeout = duration(yaml, "cp.enforcement-gateway.read-timeout");
        assertThat(gatewayConnectTimeout).isEqualTo(Duration.ofSeconds(5));
        assertThat(gatewayReadTimeout).isEqualTo(Duration.ofSeconds(50));
        // Each hop must give up before its caller, so the caller outlives the gateway's own budget.
        assertThat(gatewayReadTimeout.toMillis()).isGreaterThan(GATEWAY_TOTAL_BUDGET_MS);
        // End to end (connect + read) stays under the 60s success criterion SC-006.
        assertThat(gatewayConnectTimeout.plus(gatewayReadTimeout)).isLessThan(Duration.ofSeconds(60));
    }

    private static Duration duration(final PropertySource<?> yaml, final String name) {
        return DurationStyle.detectAndParse(String.valueOf(yaml.getProperty(name)));
    }
}
