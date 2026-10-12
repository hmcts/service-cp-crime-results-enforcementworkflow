package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Feature 002 T037/T056: the hand-over call to hearing-nows; never throws, never retried. */
@ExtendWith(OutputCaptureExtension.class)
class HearingNowsClientTest {

    private static final String BASE_URL = "http://cp-backend.test";
    private static final String URL = BASE_URL + "/hearingnows-command-api/command/api/rest/hearingnows/enforcement-nows-data";
    private static final String CJSCPPUID = "11111111-2222-3333-4444-555555555555";
    private static final String PAYLOAD = "{\"caseUrn\":\"E012345678\"}";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HearingNowsClient client = new HearingNowsClient(builder, BASE_URL, CJSCPPUID);

    @Test
    void accepted_should_be_handed_over() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("Content-Type", "application/vnd.hearingnows.add-enforcement-nows-data+json"))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andExpect(content().json(PAYLOAD))
                .andRespond(withStatus(ACCEPTED));

        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.HandedOver());
        server.verify();
    }

    @Test
    void error_statuses_should_be_failures_with_the_status() {
        server.expect(requestTo(URL)).andRespond(withStatus(BAD_REQUEST));
        server.expect(requestTo(URL)).andRespond(withStatus(FORBIDDEN));
        server.expect(requestTo(URL)).andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("HTTP 400"));
        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("HTTP 403"));
        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("HTTP 500"));
    }

    // the command answers 202; anything else is not an accepted hand-over
    @Test
    void other_success_status_should_be_a_failure() {
        server.expect(requestTo(URL)).andRespond(withStatus(OK));

        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("HTTP 200"));
    }

    @Test
    void no_response_should_be_a_failure_with_the_cause_class() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("ResourceAccessException"));
        assertThat(client.handOver(PAYLOAD)).isEqualTo(new HandoverResult.Failed("ResourceAccessException"));
    }

    // OQ-5: hearing-nows rejects a CJSCPPUID outside "System Users", so the placeholder is flagged at startup
    @Test
    void placeholder_cjscppuid_should_be_warned_about(final CapturedOutput output) {
        new HearingNowsClient(RestClient.builder(), BASE_URL, HearingNowsClient.PLACEHOLDER_CJSCPPUID);

        assertThat(output.getAll()).contains("HEARING_NOWS_CJSCPPUID is the placeholder");
    }

    @Test
    void configured_cjscppuid_should_not_be_warned_about(final CapturedOutput output) {
        new HearingNowsClient(RestClient.builder(), BASE_URL, CJSCPPUID);

        assertThat(output.getAll()).doesNotContain("HEARING_NOWS_CJSCPPUID is the placeholder");
    }
}
