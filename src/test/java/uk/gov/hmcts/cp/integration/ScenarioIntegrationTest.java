package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.client.ReferenceDataClient;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.service.ShareCorrelationId;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.GatewayContract;
import uk.gov.hmcts.cp.support.HearingNowsContract;
import uk.gov.hmcts.cp.support.LibraContract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every scenario folder under {@code src/test/resources/scenarios/} through the whole flow: the
 * event is published on {@code public.event}, reference data and the gateway answer as the scenario
 * says, and the gateway POSTs and the stored row are checked against its expectations (research.md
 * R25). Every request the gateway receives must satisfy both the gateway and the Libra contract, and
 * every stubbed 2xx reply the gateway contract; every hand-over to hearing-nows must satisfy hearing-nows'
 * command schema (feature 002). New behaviour is covered by adding a folder; the format is described in
 * quickstart.md ("Integration test scenarios").
 */
@ExtendWith(OutputCaptureExtension.class)
class ScenarioIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final String SCENARIOS = "scenarios";
    private static final String ANY_DEFINITION = "*";
    /** Defendant PII in the enforcement fixture and the fixtures derived from it. */
    private static final List<String> NEVER_LOGGED = List.of("Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street",
            "02081234567", "07700900123");
    // strict, so a mistyped field in a scenario fails the test instead of being ignored
    private static final ObjectMapper SCENARIO_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Autowired
    private ReferenceDataClient referenceDataClient;

    static Stream<Named<Path>> scenarios() throws IOException, URISyntaxException {
        final URL root = ScenarioIntegrationTest.class.getClassLoader().getResource(SCENARIOS);
        assertThat(root).as("src/test/resources/" + SCENARIOS).isNotNull();
        try (Stream<Path> folders = Files.list(Path.of(root.toURI()))) {
            final List<Path> sorted = folders.filter(Files::isDirectory).sorted().toList();
            assertThat(sorted).as("scenario folders").isNotEmpty();
            return sorted.stream().map(folder -> Named.of(folder.getFileName().toString(), folder));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(final Path folder, final CapturedOutput output) {
        final Scenario scenario = read(folder.resolve("scenario.json"));
        STUBS.resetAll();
        referenceDataClient.clearCache();
        scenario.referenceData().forEach(ScenarioIntegrationTest::stubReferenceData);
        if (scenario.gateway() != null) {
            stubGatewayReply(scenario.gateway());
        }
        stubHearingNows(scenario.hearingNowsStatus());

        scenario.eventsInOrder().forEach(event -> publish(scenario.cppName(), Fixtures.json(event)));
        awaitProcessed();

        final Expected expected = scenario.expected();
        final List<LoggedRequest> posts = STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(posts).as("gateway POSTs").hasSize(expected.gatewayPosts());
        posts.forEach(post -> assertRequestMeetsContracts(post.getBodyAsString()));
        // several shares (feature 002): exactly these GOB result codes in each request, in the order received
        if (!expected.resultCodesPerPost().isEmpty()) {
            assertThat(posts.stream().map(post -> resultCodesOf(post.getBodyAsString())).toList()).as("result codes per gateway POST")
                    .isEqualTo(expected.resultCodesPerPost());
        }
        final Path expectedRequest = folder.resolve("expected-request.json");
        if (Files.exists(expectedRequest)) {
            assertThat(posts).singleElement().satisfies(post -> assertMatches(expectedRequest, post));
        }
        // several shares: expected-request-1.json, expected-request-2.json, ... in the order the gateway received them
        for (int i = 1; Files.exists(folder.resolve("expected-request-" + i + ".json")); i++) {
            assertMatches(folder.resolve("expected-request-" + i + ".json"), posts.get(i - 1));
        }
        assertHandover(expected.handover());
        if (expected.statuses().isEmpty()) {
            assertRow(expected);
        } else {
            assertThat(repository.findAll().stream().sorted(Comparator.comparing(HearingResultSubmissionEntity::getCreatedAt))
                    .map(HearingResultSubmissionEntity::getStatus).toList()).as("stored rows' statuses").isEqualTo(expected.statuses());
        }
        // FR-017: the fixtures' defendant PII is never logged, whatever the outcome
        Stream.concat(NEVER_LOGGED.stream(), expected.logMustNotContain().stream())
                .forEach(text -> assertThat(output.getAll()).as("log").doesNotContain(text));
        expected.logMustContain().forEach(text -> assertThat(output.getAll()).as("log").contains(text));
    }

    private static void assertMatches(final Path expectedRequest, final LoggedRequest post) {
        assertThat(equalToJson(readString(expectedRequest), true, false).match(post.getBodyAsString()).isExactMatch())
                .as("request equals " + expectedRequest.getFileName()).isTrue();
    }

    /** Feature 002: hand-overs to hearing-nows; absent expectation = none. Each one must satisfy hearing-nows' schema. */
    private void assertHandover(final Handover expected) {
        final List<LoggedRequest> handovers = STUBS.findAll(postRequestedFor(urlEqualTo(HEARING_NOWS_PATH)));
        assertThat(handovers).as("hand-over POSTs").hasSize(expected == null ? 0 : expected.posts());
        handovers.forEach(post -> assertThat(HearingNowsContract.addEnforcementNowsDataViolations(post.getBodyAsString()))
                .as("hearing-nows contract violations").isEmpty());
        // the correlation id is always the one hearing-nows recomputes from the hand-over's hearing id and day (research R18)
        handovers.forEach(post -> {
            final JsonNode body = Fixtures.MAPPER.readTree(post.getBodyAsString());
            assertThat(body.path("correlationId").asString()).as("hand-over correlationId").isEqualTo(ShareCorrelationId.forShare(
                    UUID.fromString(body.path("hearingId").asString()), LocalDate.parse(body.path("hearingDay").asString())).toString());
        });
        if (expected != null && !expected.hearingDayPerPost().isEmpty()) {
            assertThat(handovers.stream().map(post -> Fixtures.MAPPER.readTree(post.getBodyAsString()).path("hearingDay").asString())
                    .toList()).as("hand-over hearingDay per POST").isEqualTo(expected.hearingDayPerPost());
        }
        if (expected != null && !expected.sharedTimePerPost().isEmpty()) {
            assertThat(handovers.stream().map(post -> Instant.parse(Fixtures.MAPPER.readTree(post.getBodyAsString()).path("sharedTime")
                    .asString())).toList()).as("hand-over sharedTime per POST")
                    .isEqualTo(expected.sharedTimePerPost().stream().map(Instant::parse).toList());
        }
        if (expected != null && !expected.gobDataReusedPerPost().isEmpty()) {
            assertThat(handovers.stream().map(post -> Fixtures.MAPPER.readTree(post.getBodyAsString()).path("gobDataReused").asBoolean())
                    .toList()).as("hand-over gobDataReused per POST").isEqualTo(expected.gobDataReusedPerPost());
        }
        if (expected != null && !expected.coveredResultDefinitionIdsPerPost().isEmpty()) {
            assertThat(handovers.stream().map(post -> Fixtures.MAPPER.readTree(post.getBodyAsString()).path("coveredResultDefinitionIds")
                    .valueStream().map(JsonNode::asString).toList()).toList()).as("hand-over coveredResultDefinitionIds per POST")
                    .isEqualTo(expected.coveredResultDefinitionIdsPerPost());
        }
        if (expected != null && expected.gobDataReused() != null) {
            assertThat(handovers).as("hand-over gobDataReused").allSatisfy(post -> assertThat(
                    Fixtures.MAPPER.readTree(post.getBodyAsString()).path("gobDataReused").asBoolean()).isEqualTo(expected.gobDataReused()));
        }
        if (expected != null && expected.status() != null) {
            assertThat(repository.findAll()).as("rows with a hand-over")
                    .filteredOn(row -> row.getNowsHandoverStatus() != null)
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.getNowsHandoverStatus()).isEqualTo(expected.status()));
            // GOB accepted the share, so its hand-over must have been recorded
            assertThat(repository.findAll()).as("SUCCEEDED rows without a recorded hand-over")
                    .filteredOn(row -> row.getStatus() == SubmissionStatus.SUCCEEDED && row.getNowsHandoverStatus() == null).isEmpty();
        }
        if (expected != null && !expected.detailsPerRow().isEmpty()) {
            assertThat(repository.findAll().stream().filter(row -> row.getNowsHandoverStatus() != null)
                    .sorted(Comparator.comparing(HearingResultSubmissionEntity::getCreatedAt))
                    .map(HearingResultSubmissionEntity::getNowsHandoverDetail).toList())
                    .as("nows_handover_detail per row with a hand-over, oldest first").isEqualTo(expected.detailsPerRow());
        }
    }

    private void assertRow(final Expected expected) {
        final List<HearingResultSubmissionEntity> rows = repository.findAll();
        if (expected.status() == null) {
            assertThat(rows).as("stored rows").isEmpty();
            return;
        }
        assertThat(rows).as("stored rows").singleElement().satisfies(row -> {
            assertThat(row.getStatus()).as("status").isEqualTo(expected.status());
            assertThat(row.getHttpStatus()).as("http_status").isEqualTo(expected.httpStatus());
            if (expected.errorDetail() != null) {
                assertThat(row.getErrorDetail()).as("error_detail").isEqualTo(expected.errorDetail());
            } else if (expected.errorDetailStartsWith() != null) {
                assertThat(row.getErrorDetail()).as("error_detail").startsWith(expected.errorDetailStartsWith());
            } else {
                assertThat(row.getErrorDetail()).as("error_detail").isNull();
            }
            // the request is stored exactly when it was sent to the gateway
            assertThat(row.getRequestPayload() != null).as("request_payload stored").isEqualTo(expected.gatewayPosts() > 0);
            expected.responsePayloadContains().forEach(text -> assertThat(row.getResponsePayload()).as("response_payload").contains(text));
        });
    }

    private static List<String> resultCodesOf(final String body) {
        return Fixtures.MAPPER.readTree(body).path("results").valueStream().map(result -> result.path("resultCode").asString()).toList();
    }

    private static void assertRequestMeetsContracts(final String body) {
        assertThat(GatewayContract.hearingResultedRequestViolations(body)).as("gateway contract violations").isEmpty();
        assertThat(LibraContract.hearingResultedRequestViolations(body)).as("Libra contract violations").isEmpty();
    }

    /** A shortCode (text) answers 200; {@code {"status": N}} answers with that status. Key "*" matches any definition. */
    private static void stubReferenceData(final String definitionId, final JsonNode answer) {
        final MappingBuilder request = ANY_DEFINITION.equals(definitionId)
                ? get(urlPathMatching(DEFINITIONS + ".*")).atPriority(10)
                : get(urlPathEqualTo(DEFINITIONS + definitionId)).atPriority(1);
        final ResponseDefinitionBuilder response = answer.isString()
                ? aResponse().withStatus(200).withHeader("Content-Type", "application/vnd.referencedata.get-result-definition+json")
                        .withBody("{\"id\":\"" + definitionId + "\",\"shortCode\":\"" + answer.asString() + "\"}")
                : aResponse().withStatus(answer.path("status").asInt());
        STUBS.stubFor(request.willReturn(response));
    }

    private static void stubGatewayReply(final GatewayReply reply) {
        final String body = reply.bodyText() != null ? reply.bodyText() : Fixtures.MAPPER.writeValueAsString(reply.body());
        if (reply.status() / 100 == 2 && reply.body() != null) {
            assertThat(GatewayContract.hearingResultedResponseViolations(body)).as("stubbed reply violates the gateway contract").isEmpty();
        }
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH)).willReturn(aResponse().withStatus(reply.status())
                .withHeader("Content-Type", "application/json").withBody(body).withFixedDelay(reply.delayMs())));
    }

    private static Scenario read(final Path file) {
        return SCENARIO_MAPPER.readValue(readString(file), Scenario.class);
    }

    private static String readString(final Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One scenario.json.
     *
     * @param event         event fixture on the test classpath, e.g. {@code events/hearing-resulted-enforcement.json}
     * @param events        instead of {@code event}: fixtures published in order (a redelivery, or a first share then a reshare)
     * @param cppName       the {@code CPPNAME} it is published under; defaults to the hearing-resulted event
     * @param referenceData result type id (or "*") → shortCode, or {@code {"status": N}}; unlisted ids answer 404
     * @param gateway       the gateway's reply; omitted when the scenario must not reach the gateway
     */
    private record Scenario(String description, String event, List<String> events, String cppName,
                            Map<String, JsonNode> referenceData, GatewayReply gateway, Integer hearingNowsStatus, Expected expected) {
        Scenario {
            if ((event == null) == (events == null)) {
                throw new IllegalArgumentException("give exactly one of event and events");
            }
            cppName = cppName == null ? HEARING_RESULTED : cppName;
            referenceData = referenceData == null ? Map.of() : referenceData;
            hearingNowsStatus = hearingNowsStatus == null ? 202 : hearingNowsStatus;
        }

        List<String> eventsInOrder() {
            return events == null ? List.of(event) : events;
        }
    }

    /** {@code body} is JSON; {@code bodyText} is sent as-is (e.g. a reply that isn't JSON). */
    private record GatewayReply(int status, JsonNode body, String bodyText, Integer delayMs) {
        GatewayReply {
            delayMs = delayMs == null ? 0 : delayMs;
        }
    }

    /**
     * What the scenario must leave behind. {@code status} null means no row. {@code httpStatus} and the
     * error detail are always checked (absent = null); give {@code errorDetail} or {@code errorDetailStartsWith}.
     * {@code statuses} instead of {@code status}: the statuses of several rows (several shares), oldest first.
     * {@code resultCodesPerPost}: the GOB result codes of each gateway POST, in order (several shares).
     * {@code handover}: the hand-overs to hearing-nows (feature 002); absent = none.
     */
    private record Expected(int gatewayPosts, SubmissionStatus status, List<SubmissionStatus> statuses, Integer httpStatus,
                            String errorDetail, String errorDetailStartsWith, List<String> responsePayloadContains,
                            List<String> logMustNotContain, List<String> logMustContain, List<List<String>> resultCodesPerPost,
                            Handover handover) {
        Expected {
            statuses = statuses == null ? List.of() : statuses;
            resultCodesPerPost = resultCodesPerPost == null ? List.of() : resultCodesPerPost;
            responsePayloadContains = responsePayloadContains == null ? List.of() : responsePayloadContains;
            logMustNotContain = logMustNotContain == null ? List.of() : logMustNotContain;
            logMustContain = logMustContain == null ? List.of() : logMustContain;
        }
    }

    /**
     * Hand-overs to hearing-nows: how many, the outcome recorded on the rows, and their {@code gobDataReused} flag (for all
     * of them, or per POST in order); optionally each POST's covered ids, {@code hearingDay} and {@code sharedTime}, and the
     * {@code nows_handover_detail} of each row with a hand-over (oldest first; null for a plain success).
     */
    private record Handover(int posts, NowsHandoverStatus status, Boolean gobDataReused, List<Boolean> gobDataReusedPerPost,
                            List<List<String>> coveredResultDefinitionIdsPerPost, List<String> hearingDayPerPost,
                            List<String> sharedTimePerPost, List<String> detailsPerRow) {
        Handover {
            gobDataReusedPerPost = gobDataReusedPerPost == null ? List.of() : gobDataReusedPerPost;
            coveredResultDefinitionIdsPerPost = coveredResultDefinitionIdsPerPost == null ? List.of() : coveredResultDefinitionIdsPerPost;
            hearingDayPerPost = hearingDayPerPost == null ? List.of() : hearingDayPerPost;
            sharedTimePerPost = sharedTimePerPost == null ? List.of() : sharedTimePerPost;
            detailsPerRow = detailsPerRow == null ? List.of() : detailsPerRow;
        }
    }
}
