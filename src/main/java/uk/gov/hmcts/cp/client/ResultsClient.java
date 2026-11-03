package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import uk.gov.hmcts.cp.config.ResultsProperties;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads a hearing's shared results from the Results context (feature 003, contracts/results-data-sources.md §2):
 * {@code GET /hearingDetails/internal/{hearingId}} with the vendor Accept header and a System Users CJSCPPUID, the
 * service-cp-crime-results-pcr convention. It returns the latest hearing day's results (accepted interim, OP-1). The
 * body holds results and personal data: never logged.
 */
@Slf4j
@Component
public class ResultsClient {

    /* default */ static final String HEARING_DETAILS_PATH = "/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}";
    /* default */ static final String ACCEPT_HEARING_DETAILS = "application/vnd.results.hearing-details-internal+json";

    private final RestClient restClient;
    private final String cjscppuid;
    // TEMP DIAGNOSTICS: kept only for the logs
    private final String baseUrl;

    public ResultsClient(@Qualifier("resultsRestClientBuilder") final RestClient.Builder restClientBuilder,
                         final ResultsProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getResults().getBaseUrl()).build();
        this.cjscppuid = properties.getResults().getCjscppuid();
        this.baseUrl = properties.getResults().getBaseUrl();
    }

    /**
     * The hearing details as JSON, or empty when Results returns an empty body (unknown hearing, {@code 200 {}}).
     *
     * @throws ResultsUnavailableException when Results fails or can't be reached
     */
    public Optional<String> hearingDetails(final UUID hearingId) {
        // TEMP DIAGNOSTICS: the request
        TempPayloadLog.info("Results query request: GET {}{} (hearingId={}, Accept={}, CJSCPPUID={})", baseUrl,
                HEARING_DETAILS_PATH, hearingId, ACCEPT_HEARING_DETAILS, cjscppuid);
        try {
            final String body = restClient.get()
                    .uri(HEARING_DETAILS_PATH, hearingId)
                    .header("Accept", ACCEPT_HEARING_DETAILS)
                    .header("CJSCPPUID", cjscppuid)
                    .retrieve()
                    .body(String.class);
            // TEMP DIAGNOSTICS: the full answer
            TempPayloadLog.info("Results query response for hearing {}: body={}", hearingId, body);
            return Optional.ofNullable(body).filter(json -> !json.isBlank() && !"{}".equals(json.strip()));
        } catch (RestClientResponseException e) {
            log.warn("Results hearing details for hearing {} failed: HTTP {}", hearingId, e.getStatusCode().value());
            // TEMP DIAGNOSTICS: status, response body and stack trace
            TempPayloadLog.error("Results query for hearing {} failed: HTTP {} {}, body={}", hearingId, e.getStatusCode().value(),
                    e.getStatusText(), e.getResponseBodyAsString(), e);
            throw new ResultsUnavailableException("Results hearing details failed: HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("Results hearing details for hearing {} failed: {}", hearingId, e.getClass().getSimpleName());
            // TEMP DIAGNOSTICS: message, root cause and stack trace
            TempPayloadLog.error("Results query for hearing {} failed: {} - {} (rootCause={})", hearingId,
                    e.getClass().getSimpleName(), e.getMessage(), TempDiagnostics.rootCause(e).toString(), e);
            throw new ResultsUnavailableException("Results hearing details failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
