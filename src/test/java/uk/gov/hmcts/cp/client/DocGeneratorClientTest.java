package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.ClientProperties;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class DocGeneratorClientTest {

    private static final String BASE_URL = "http://doc-generator.test";
    private static final String URL = BASE_URL
            + "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/documents/generate-document";
    private static final UUID CORRELATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DocGeneratorClient client = new DocGeneratorClient(builder, properties());

    private static ClientProperties properties() {
        final ClientProperties properties = new ClientProperties();
        properties.getDocGenerator().setBaseUrl(BASE_URL);
        return properties;
    }

    private static GenerateDocumentCommand command() {
        return new GenerateDocumentCommand("enforcement-result-notification-first-share",
                GenerateDocumentCommand.CONVERSION_FORMAT_PDF, Instant.parse("2026-11-24T10:00:00Z"),
                "result-notification/requests/" + CORRELATION_ID, "result-notification/documents/" + CORRELATION_ID + ".pdf",
                "service-cp-crime-results-enforcementworkflow", CORRELATION_ID);
    }

    @Test
    void should_post_the_generate_document_command_with_every_required_field() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.templateIdentifier").value("enforcement-result-notification-first-share"))
                .andExpect(jsonPath("$.conversionFormat").value("pdf"))
                .andExpect(jsonPath("$.payloadFileUri").value("result-notification/requests/" + CORRELATION_ID))
                .andExpect(jsonPath("$.destinationFileUri").value("result-notification/documents/" + CORRELATION_ID + ".pdf"))
                .andExpect(jsonPath("$.sourceCorrelationId").value(CORRELATION_ID.toString()))
                .andRespond(withStatus(ACCEPTED));

        client.generateDocument(command());
        server.verify();
    }

    @Test
    void server_error_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.generateDocument(command())).isInstanceOf(DocGenerationUnavailableException.class);
    }

    @Test
    void transport_failure_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.generateDocument(command())).isInstanceOf(DocGenerationUnavailableException.class);
    }
}
