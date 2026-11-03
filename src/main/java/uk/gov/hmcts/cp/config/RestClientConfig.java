package uk.gov.hmcts.cp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * One named {@link RestClient.Builder} per outbound target, each with its own connect/read timeouts, read from
 * {@link ClientProperties} and {@link ResultsProperties}.
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
    public RestClient.Builder referenceDataRestClientBuilder(final ClientProperties properties) {
        final ClientProperties.ReferenceData referenceData = properties.getReferenceData();
        return builderWithTimeouts(referenceData.getConnectTimeout(), referenceData.getReadTimeout());
    }

    @Bean(name = "enforcementGatewayRestClientBuilder")
    @Scope("prototype")
    public RestClient.Builder enforcementGatewayRestClientBuilder(final ClientProperties properties) {
        final ClientProperties.EnforcementGateway gateway = properties.getEnforcementGateway();
        return builderWithTimeouts(gateway.getConnectTimeout(), gateway.getReadTimeout());
    }

    /** Results query (feature 003, research R17): stage 1 reads the share's results. */
    @Bean(name = "resultsRestClientBuilder")
    @Scope("prototype")
    public RestClient.Builder resultsRestClientBuilder(final ResultsProperties properties) {
        final ResultsProperties.Results results = properties.getResults();
        return builderWithTimeouts(results.getConnectTimeout(), results.getReadTimeout());
    }

    /* default */ static RestClient.Builder builderWithTimeouts(final Duration connectTimeout, final Duration readTimeout) {
        @SuppressWarnings("PMD.CloseResource") // wrapped into requestFactory below and kept open for the bean's lifetime, not closed here
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
