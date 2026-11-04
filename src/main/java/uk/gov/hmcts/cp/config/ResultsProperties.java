package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code cp.results.*} and {@code cp.results-cache.*}: where stage 1 reads the share's results (feature 003, research
 * R7, R8, contracts/results-data-sources.md).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp")
public class ResultsProperties {

    private final Results results = new Results();
    private final ResultsCache resultsCache = new ResultsCache();

    @Getter
    @Setter
    public static class Results {
        /** Results context host only; the client appends {@code /results-query-api/...}. */
        @NotBlank
        private String baseUrl = "http://localhost:8080";
        /** A System Users member (results-query-api access rules). Required: checked at startup by {@link StartupPropertiesValidator}. */
        private String cjscppuid;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /**
     * The Results cache, all in one place as in service-cp-crime-informant-register. The address defaults are for local
     * development; deployed environments set them through environment variables bound by name (for example
     * {@code CP_RESULTSCACHE_HOST} for {@code cp.results-cache.host}).
     */
    @Getter
    @Setter
    public static class ResultsCache {
        /** Off until the Results cache accepts Entra ID for this service's managed identity (research R8, OP-4). */
        private boolean enabled;
        private String host = "localhost";
        private int port = 6379;
        /** TLS, with the certificate verified. Off for a local server, on in every deployed environment. */
        private boolean ssl;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration commandTimeout = Duration.ofSeconds(2);
        /** Entra ID username for the cache: the object id of this service's managed identity. */
        private String username;
        /** Managed identity client id; empty uses the default credential chain. */
        private String managedIdentityClientId;
    }
}
