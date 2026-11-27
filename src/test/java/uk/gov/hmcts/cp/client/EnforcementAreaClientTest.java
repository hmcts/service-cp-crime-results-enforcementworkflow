package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.ClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EnforcementAreaClientTest {

    private static final String BASE_URL = "http://enforcement-area.test";
    private static final String CJSCPPUID = "11111111-2222-3333-4444-555555555555";
    private static final String URL = BASE_URL + "/reference-data-query-api/query/api/rest/enforcement-area/internal/10";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EnforcementAreaClient client = new EnforcementAreaClient(builder, properties());

    private static ClientProperties properties() {
        final ClientProperties properties = new ClientProperties();
        properties.getReferenceData().setBaseUrl(BASE_URL);
        properties.getReferenceData().setCjscppuid(CJSCPPUID);
        return properties;
    }

    @Test
    void should_return_team_inbox_with_cjscppuid_header() {
        server.expect(requestTo(URL))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andRespond(withSuccess("{\"divisionCode\":\"010\",\"teamEmail\":\"team@example.gov.uk\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findTeamInbox(10)).contains(new TeamInbox("team@example.gov.uk"));
        server.verify();
    }

    @Test
    void not_found_should_return_empty_never_cached() {
        server.expect(requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.findTeamInbox(10)).isEmpty();
    }

    @Test
    void server_error_should_throw_unavailable_not_collapsed_into_not_found() {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findTeamInbox(10)).isInstanceOf(TeamInboxUnavailableException.class);
    }

    @Test
    void transport_failure_should_throw_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findTeamInbox(10)).isInstanceOf(TeamInboxUnavailableException.class);
    }
}
