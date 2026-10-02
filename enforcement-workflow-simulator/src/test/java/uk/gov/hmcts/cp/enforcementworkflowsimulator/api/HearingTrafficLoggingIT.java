package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request and response bodies on the {@code HearingController} endpoints are logged at INFO as
 * nested JSON objects, not escaped strings (ADR-005). Asserted on the real stdout JSON lines the
 * logback encoder writes, so the {@code arguments} provider wiring is covered too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@ExtendWith(OutputCaptureExtension.class)
class HearingTrafficLoggingIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CONFIRMATION = """
            {
              "caseUrn": "E011122334",
              "courtHearingLocation": "B02BR03",
              "dateOfHearing": "2026-04-24",
              "timeOfHearing": "14:00"
            }
            """;

    private static final String RESULT = """
            {
              "caseUrn": "E012345678",
              "dateOfHearing": "2026-05-03",
              "courtHearingLocation": "B01BH01",
              "defendantDetails": { "prosecutorDefendantId": "1234567890", "address1": "1 Example Street" },
              "enforcement": { "prisonSentenceIndicator": "N" },
              "results": [ { "resultCode": "SC" } ],
              "nowsDataRequest": { "nowsDataItems": [ { "name": "Account Balance" } ] }
            }
            """;

    @Resource
    private MockMvc mockMvc;

    private String authorization;

    @BeforeEach
    void obtainBearerToken() throws Exception {
        authorization = BearerTokens.authorizationHeader(mockMvc);
    }

    @Test
    void logs_the_hearing_result_request_and_response_bodies_as_nested_json(final CapturedOutput output)
            throws Exception {
        mockMvc.perform(post("/hearing/result").header(AUTHORIZATION, authorization)
                        .header("X-Correlation-ID", "log-test-correlation")
                        .contentType(APPLICATION_JSON).content(RESULT))
                .andExpect(status().isOk());

        final JsonNode request = onlyLine(output, "Hearing request received");
        assertThat(request.path("level").asText()).isEqualTo("INFO");
        assertThat(request.path("httpMethod").asText()).isEqualTo("POST");
        assertThat(request.path("path").asText()).isEqualTo("/hearing/result");
        assertThat(request.path("correlationId").asText()).isEqualTo("log-test-correlation");
        assertThat(request.path("requestBody").isObject()).isTrue();
        assertThat(request.path("requestBody").path("results").get(0).path("resultCode").asText()).isEqualTo("SC");

        final JsonNode response = onlyLine(output, "Hearing response sent");
        assertThat(response.path("level").asText()).isEqualTo("INFO");
        assertThat(response.path("status").asInt()).isEqualTo(200);
        assertThat(response.path("responseBody").isObject()).isTrue();
        assertThat(response.path("responseBody").path("nowsDataItems").path("accountBalance").isNumber()).isTrue();
    }

    /** The log must show the body as sent, so a decimal keeps its scale rather than becoming a double. */
    @Test
    void logs_decimals_exactly_as_they_appear_in_the_response(final CapturedOutput output) throws Exception {
        final String body = mockMvc.perform(post("/hearing/result").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON).content(RESULT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"accountBalance\":1250.00");

        final String responseLine = output.getOut().lines()
                .filter(line -> line.contains("\"message\":\"Hearing response sent\""))
                .findFirst().orElseThrow();
        assertThat(responseLine).contains("\"accountBalance\":1250.00");
    }

    @Test
    void logs_a_confirmation_whose_response_has_no_body(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/hearing").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON).content(CONFIRMATION))
                .andExpect(status().isOk());

        assertThat(onlyLine(output, "Hearing request received").path("requestBody").path("courtHearingLocation").asText())
                .isEqualTo("B02BR03");
        final JsonNode response = onlyLine(output, "Hearing response sent");
        assertThat(response.path("status").asInt()).isEqualTo(200);
        assertThat(response.has("responseBody")).isFalse();
    }

    /** Rejections happen before the controller runs, and are exactly what a Postman user needs to see. */
    @Test
    void logs_a_rejected_request_and_its_error_body(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/hearing").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON).content("{ \"caseUrn\": \"E011122334\" }"))
                .andExpect(status().isBadRequest());

        final JsonNode response = onlyLine(output, "Hearing response sent");
        assertThat(response.path("status").asInt()).isEqualTo(400);
        assertThat(response.path("responseBody").path("errorCode").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void logs_a_body_that_is_not_json_as_a_plain_string(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/hearing").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());

        assertThat(onlyLine(output, "Hearing request received").path("requestBody").asText()).isEqualTo("not json");
    }

    @Test
    void never_logs_the_bearer_token(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/hearing").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON).content(CONFIRMATION))
                .andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain(authorization.substring("Bearer ".length()));
    }

    private static JsonNode onlyLine(final CapturedOutput output, final String message) throws Exception {
        final List<JsonNode> matches = output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(HearingTrafficLoggingIT::parse)
                .filter(node -> message.equals(node.path("message").asText()))
                .toList();
        assertThat(matches).as("exactly one '%s' log line", message).hasSize(1);
        return matches.get(0);
    }

    private static JsonNode parse(final String line) {
        try {
            return JSON.readTree(line);
        } catch (final IOException e) {
            throw new IllegalStateException("stdout line is not valid JSON: " + line, e);
        }
    }
}
