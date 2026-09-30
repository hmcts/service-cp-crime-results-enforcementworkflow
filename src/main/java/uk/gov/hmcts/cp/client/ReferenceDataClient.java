package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up a result's shortCode in reference data: {@code GET /result-definitions/{id}?on=} with the
 * vendor Accept header and CJSCPPUID (service-cp-crime-results-pcr's ReferenceDataClient convention;
 * contracts/reference-data-result-definition.md). Iteration 1 caches found shortCodes per
 * {@code (id, on)} in memory. A 404 is not cached, so a definition added later is picked up. Any
 * other failure throws {@link ReferenceDataUnavailableException}. The bulk {@code cacheable} load
 * comes later (research.md R4b).
 */
@Slf4j
@Component
public class ReferenceDataClient {

    /* default */ static final String RESULT_DEFINITION_PATH = "/referencedata-query-api/query/api/rest/referencedata/result-definitions/{id}";
    private static final String ACCEPT_RESULT_DEFINITION = "application/vnd.referencedata.get-result-definition+json";

    private final RestClient restClient;
    private final String cjscppuid;
    private final Map<CacheKey, Optional<String>> cache = new ConcurrentHashMap<>();

    public ReferenceDataClient(@Qualifier("referenceDataRestClientBuilder") final RestClient.Builder restClientBuilder,
                               @Value("${cp.reference-data.base-url}") final String baseUrl,
                               @Value("${cp.reference-data.cjscppuid}") final String cjscppuid) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.cjscppuid = cjscppuid;
    }

    /**
     * The result definition's shortCode on the given date, or empty when reference data has no such definition (404).
     *
     * @throws ReferenceDataUnavailableException when reference data fails or can't be reached
     */
    public Optional<String> findShortCode(final UUID resultDefinitionId, final LocalDate on) {
        final CacheKey key = new CacheKey(resultDefinitionId, on);
        final Optional<String> cached = cache.get(key);
        final Optional<String> shortCode = cached != null ? cached : fetch(resultDefinitionId, on);
        shortCode.ifPresent(code -> cache.putIfAbsent(key, shortCode));
        return shortCode;
    }

    /** Drops every cached shortCode. Used by the scenario integration tests, and the hook for T081's scheduled refresh. */
    public void clearCache() {
        cache.clear();
    }

    private Optional<String> fetch(final UUID resultDefinitionId, final LocalDate on) {
        Optional<String> shortCode;
        try {
            final ResultDefinition definition = restClient.get()
                    .uri(uri -> uri.path(RESULT_DEFINITION_PATH).queryParam("on", on).build(resultDefinitionId))
                    .header("Accept", ACCEPT_RESULT_DEFINITION)
                    .header("CJSCPPUID", cjscppuid)
                    .retrieve()
                    .body(ResultDefinition.class);
            shortCode = Optional.ofNullable(definition).map(ResultDefinition::shortCode);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("No reference-data result definition {} on {}", resultDefinitionId, on);
            shortCode = Optional.empty();
        } catch (RestClientException e) {
            throw new ReferenceDataUnavailableException("Reference data lookup failed for result definition "
                    + resultDefinitionId + ": " + e.getClass().getSimpleName(), e);
        }
        return shortCode;
    }

    private record CacheKey(UUID id, LocalDate on) {
    }
}
