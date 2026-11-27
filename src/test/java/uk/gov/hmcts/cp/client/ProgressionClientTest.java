package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.ClientProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Feature 005 FR-020 (contracts/progression-court-document.md): both calls hit the same URL, distinguished by
 * Content-Type - multipart for the upload (Progression derives its own fileServiceId), JSON for the attach.
 */
class ProgressionClientTest {

    private static final String BASE_URL = "http://progression.test";
    private static final String CJSCPPUID = "22222222-3333-4444-5555-666666666666";
    private static final UUID MATERIAL_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID PROSECUTION_CASE_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final String URL = BASE_URL + "/progression-command-api/command/api/rest/progression/courtdocument/" + MATERIAL_ID;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ProgressionClient client = new ProgressionClient(builder, properties());

    private static ClientProperties properties() {
        final ClientProperties properties = new ClientProperties();
        properties.getProgression().setBaseUrl(BASE_URL);
        properties.getProgression().setCjscppuid(CJSCPPUID);
        return properties;
    }

    @Test
    void upload_file_should_post_multipart_with_cjscppuid_header() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.MULTIPART_FORM_DATA))
                .andRespond(withStatus(ACCEPTED));

        client.uploadFile(MATERIAL_ID, "pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.verify();
    }

    @Test
    void upload_file_server_error_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.uploadFile(MATERIAL_ID, "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(ProgressionUnavailableException.class);
    }

    @Test
    void upload_file_transport_failure_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.uploadFile(MATERIAL_ID, "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(ProgressionUnavailableException.class);
    }

    @Test
    void add_court_document_should_post_the_court_document_with_every_required_field() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andExpect(jsonPath("$.courtDocument.documentTypeId").value("f471eb51-614c-4447-bd8d-28f9c2815c9e"))
                .andExpect(jsonPath("$.courtDocument.documentCategory.caseDocument.prosecutionCaseId").value(PROSECUTION_CASE_ID.toString()))
                .andExpect(jsonPath("$.courtDocument.materials[0].id").value(MATERIAL_ID.toString()))
                .andExpect(jsonPath("$.courtDocument.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.courtDocument.containsFinancialMeans").value(false))
                .andRespond(withStatus(ACCEPTED));

        client.addCourtDocument(MATERIAL_ID, PROSECUTION_CASE_ID);
        server.verify();
    }

    @Test
    void add_court_document_server_error_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.addCourtDocument(MATERIAL_ID, PROSECUTION_CASE_ID))
                .isInstanceOf(ProgressionUnavailableException.class);
    }

    @Test
    void add_court_document_transport_failure_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.addCourtDocument(MATERIAL_ID, PROSECUTION_CASE_ID))
                .isInstanceOf(ProgressionUnavailableException.class);
    }
}
