package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import uk.gov.hmcts.cp.config.ClientProperties;

/**
 * Sends the {@code generate-document} BYO-FS command to System Doc Generator (feature 005, contracts/
 * system-doc-generator-byo-fs.md; branch {@code team/CHD-2786}, unmerged at time of writing - go-live gate, plan.md
 * D4). Command only - no query/poll method, since completion is event-driven ({@code DocGeneratorEventListener}).
 * The exact REST path is this feature's own assumption pending re-confirmation against the branch's current HEAD
 * (plan.md D4) before go-live.
 */
@Slf4j
@Component
public class DocGeneratorClient {

    /* default */ static final String GENERATE_DOCUMENT_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/documents/generate-document";

    private final RestClient restClient;

    public DocGeneratorClient(@Qualifier("docGeneratorRestClientBuilder") final RestClient.Builder restClientBuilder,
                              final ClientProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getDocGenerator().getBaseUrl()).build();
    }

    /** @throws DocGenerationUnavailableException when the command can't be accepted */
    public void generateDocument(final GenerateDocumentCommand command) {
        try {
            restClient.post()
                    .uri(GENERATE_DOCUMENT_PATH)
                    .body(command)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("generate-document command rejected for correlation {}: HTTP {} {}", command.sourceCorrelationId(),
                    e.getStatusCode().value(), e.getStatusText());
            throw new DocGenerationUnavailableException("generate-document command rejected for correlation "
                    + command.sourceCorrelationId() + ": " + e.getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            log.warn("generate-document command failed for correlation {}: {} - {}", command.sourceCorrelationId(),
                    e.getClass().getSimpleName(), e.getMessage());
            throw new DocGenerationUnavailableException("generate-document command failed for correlation "
                    + command.sourceCorrelationId() + ": " + e.getClass().getSimpleName(), e);
        }
    }
}
