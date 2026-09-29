package uk.gov.hmcts.cp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * One named {@link RestClient.Builder} per outbound target, each with its own connect/read timeouts.
 * The timeout budget (research.md R20, constitution Principle V) requires every hop to give up
 * before its caller. The workflow → gateway read timeout (50s) must therefore exceed the gateway's
 * own total towards APIM (5s + 40s). Reference data is a separate, shorter call.
 *
 * <p>Prototype-scoped: {@link RestClient.Builder} is mutable ({@code baseUrl(...)} mutates in place),
 * so each client needs its own instance.
 */
@Configuration
public class RestClientConfig {

    @Bean(name = "referenceDataRestClientBuilder")
    @Scope("prototype")
    public RestClient.Builder referenceDataRestClientBuilder(
            @Value("${cp.reference-data.connect-timeout-ms:5000}") final long connectTimeoutMs,
            @Value("${cp.reference-data.read-timeout-ms:10000}") final long readTimeoutMs) {
        return builderWithTimeouts(connectTimeoutMs, readTimeoutMs);
    }

    @Bean(name = "enforcementGatewayRestClientBuilder")
    @Scope("prototype")
    public RestClient.Builder enforcementGatewayRestClientBuilder(
            @Value("${cp.enforcement-gateway.connect-timeout-ms:5000}") final long connectTimeoutMs,
            @Value("${cp.enforcement-gateway.read-timeout-ms:50000}") final long readTimeoutMs) {
        return builderWithTimeouts(connectTimeoutMs, readTimeoutMs);
    }

    /* default */ static RestClient.Builder builderWithTimeouts(final long connectTimeoutMs, final long readTimeoutMs) {
        @SuppressWarnings("PMD.CloseResource") // wrapped into requestFactory below and kept open for the bean's lifetime, not closed here
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
