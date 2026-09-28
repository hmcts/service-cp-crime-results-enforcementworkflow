package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.support.Fixtures;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EnforcementGatewayClientTest {

    private static final String BASE_URL = "http://gateway.test";
    private static final String RESPONSE = """
            {"caseUrn":"E012345678","timestamp":"2026-05-03T14:30:00Z","correlationId":"c-1","nowsDataItems":{"accountBalance":125.5}}""";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EnforcementGatewayClient client = new EnforcementGatewayClient(builder, BASE_URL);

    @Test
    void success_should_return_parsed_response_and_raw_body() {
        final String requestJson = Fixtures.json("expected/hearing-resulted-request-minimum.json");
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(requestJson))
                .andExpect(content().string(not(containsString("null")))) // absent optional blocks are omitted
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        final GatewayResult result = client.submit(PayloadJson.MAPPER.readValue(requestJson, HearingResultedRequest.class));

        assertThat(result).isInstanceOfSatisfying(GatewayResult.Success.class, success -> {
            assertThat(success.httpStatus()).isEqualTo(200);
            assertThat(success.response().getCaseUrn()).isEqualTo("E012345678");
            assertThat(success.response().getCorrelationId()).isEqualTo("c-1");
            assertThat(success.rawResponse()).isEqualTo(RESPONSE);
        });
        server.verify();
    }

    @Test
    void gateway_400_should_be_a_failure_with_status_and_body() {
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andRespond(withStatus(BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"INVALID_PAYLOAD\"}"));

        assertThat(client.submit(minimumRequest())).isEqualTo(new GatewayResult.Failure(400, "error=INVALID_PAYLOAD; libraStatus=; errorCode="));
    }

    @Test
    void gateway_502_should_be_a_failure_with_status_and_body() {
        final String body = "{\"error\":\"LIBRA_CALL_FAILED\",\"details\":{\"libraStatus\":404}}";
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andRespond(withStatus(BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body(body));

        assertThat(client.submit(minimumRequest())).isEqualTo(new GatewayResult.Failure(502, "error=LIBRA_CALL_FAILED; libraStatus=404; errorCode="));
    }

    @Test
    void read_timeout_should_be_a_failure_with_unknown_outcome() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.submit(minimumRequest()))
                .isEqualTo(new GatewayResult.Failure(null, "TIMEOUT – outcome at GOB unknown"));
    }

    private static HearingResultedRequest minimumRequest() {
        return PayloadJson.MAPPER.readValue(Fixtures.json("expected/hearing-resulted-request-minimum.json"), HearingResultedRequest.class);
    }

    @Test
    void libra_error_description_should_not_be_kept() {
        final String body = "{\"error\":\"LIBRA_CALL_FAILED\",\"details\":{\"libraStatus\":400,\"errorCode\":\"E400\","
                + "\"errorDescription\":\"surname Harrison invalid\"}}";
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andRespond(withStatus(BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body(body));

        assertThat(client.submit(minimumRequest()))
                .isEqualTo(new GatewayResult.Failure(502, "error=LIBRA_CALL_FAILED; libraStatus=400; errorCode=E400"));
    }

    @Test
    void non_json_error_body_should_be_omitted() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withStatus(BAD_GATEWAY).body("<html>proxy error</html>"));

        assertThat(client.submit(minimumRequest())).isEqualTo(new GatewayResult.Failure(502, "non-JSON error body omitted"));
    }

    @Test
    void connection_refused_should_be_a_failure_that_was_not_sent() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withException(new ConnectException("Connection refused")));

        assertThat(client.submit(minimumRequest()))
                .isEqualTo(new GatewayResult.Failure(null, "NOT_SENT – enforcement gateway unreachable"));
    }

    @Test
    void unparsable_2xx_body_should_still_be_a_success_with_raw_body() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThat(client.submit(minimumRequest())).isEqualTo(new GatewayResult.Success(null, "not json", 200));
    }

    @Test
    void empty_2xx_body_should_still_be_a_success() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withSuccess());

        assertThat(client.submit(minimumRequest())).isInstanceOfSatisfying(GatewayResult.Success.class,
                s -> assertThat(s.response()).isNull());
    }
}
