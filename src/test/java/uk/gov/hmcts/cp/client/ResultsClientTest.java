package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.ResultsProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Feature 003 (contracts/results-data-sources.md §2): the Results hearing details query. */
class ResultsClientTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final String URL = "http://results.local/results-query-api/query/api/rest/results/hearingDetails/internal/" + HEARING_ID;

    private MockRestServiceServer server;
    private ResultsClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final ResultsProperties properties = new ResultsProperties();
        properties.getResults().setBaseUrl("http://results.local");
        properties.getResults().setCjscppuid("aaaaaaaa-0000-4000-8000-000000000001");
        client = new ResultsClient(builder, properties);
    }

    @Test
    void hearing_details_should_be_read_with_the_vendor_type_and_system_user() {
        server.expect(requestTo(URL)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Accept", ResultsClient.ACCEPT_HEARING_DETAILS))
                .andExpect(header("CJSCPPUID", "aaaaaaaa-0000-4000-8000-000000000001"))
                .andRespond(withSuccess("{\"hearing\":{\"id\":\"" + HEARING_ID + "\"},\"sharedTime\":\"2026-05-03T14:30:00Z\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.hearingDetails(HEARING_ID)).hasValueSatisfying(json -> assertThat(json).contains("sharedTime"));
        server.verify();
    }

    @Test
    void empty_answer_should_be_not_found() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.hearingDetails(HEARING_ID)).isEmpty();
    }

    @Test
    void blank_answer_should_be_not_found() {
        server.expect(requestTo(URL)).andRespond(withSuccess("  ", MediaType.APPLICATION_JSON));

        assertThat(client.hearingDetails(HEARING_ID)).isEmpty();
    }

    @Test
    void no_body_should_be_not_found() {
        server.expect(requestTo(URL)).andRespond(withSuccess());

        assertThat(client.hearingDetails(HEARING_ID)).isEmpty();
    }

    @Test
    void server_error_should_be_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.hearingDetails(HEARING_ID)).isInstanceOf(ResultsUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void forbidden_should_be_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.hearingDetails(HEARING_ID)).isInstanceOf(ResultsUnavailableException.class)
                .hasMessageContaining("403");
    }

    @Test
    void connection_failure_should_be_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.ConnectException("refused")));

        assertThatThrownBy(() -> client.hearingDetails(HEARING_ID)).isInstanceOf(ResultsUnavailableException.class);
    }
}
