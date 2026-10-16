package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
        /** A System Users member (results-query-api access rules). */
        @NotBlank
        private String cjscppuid = "00000000-0000-0000-0000-000000000000";
        private long connectTimeoutMs = 5000;
        private long readTimeoutMs = 10_000;
    }

    @Getter
    @Setter
    public static class ResultsCache {
        /** Off until the Results cache accepts Entra ID for this service's managed identity (research R8, OP-4). */
        private boolean enabled;
        /** Entra ID username for the cache: the object id of this service's managed identity. */
        private String username;
        /** Managed identity client id; empty uses the default credential chain. */
        private String managedIdentityClientId;
    }
}
