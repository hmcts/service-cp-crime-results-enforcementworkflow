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
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.GatewayContract;
import uk.gov.hmcts.cp.support.GobDataEventContract;
import uk.gov.hmcts.cp.support.LibraContract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/**
 * Runs every scenario folder under {@code src/test/resources/scenarios/} through the whole flow: each event fixture is
 * one share, which the Results query answers and the results-processing Function App notifies (feature 003); reference
 * data and the gateway answer as the scenario says, and the gateway POSTs, the stored rows and the published GOB data
 * events are checked against its expectations (research.md R25). Every request the gateway receives must satisfy both the
 * gateway and the Libra contract, and every stubbed 2xx reply the gateway contract; every GOB data event must satisfy
 * hearing-nows' event schema. New behaviour is covered by adding a folder; the format is described in quickstart.md
 * ("Integration test scenarios").
 */
@ExtendWith(OutputCaptureExtension.class)
class ScenarioIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final String SCENARIOS = "scenarios";
    private static final String ANY_DEFINITION = "*";
    /**
     * Defendant PII in the enforcement fixture and the fixtures derived from it, and GOB account data: the account number
     * (the defendant's prosecutionAuthorityReference, sent to GOB and published to hearing-nows) and GOB's balance.
     */
    private static final List<String> NEVER_LOGGED = List.of("Edward", "Harrison", "2002-01-10", "QQ123456C", "1 High Street",
            "02079460000", "07700900123", "Tottenham", "ZZTST001101EJ9ZZ", "ZZ25A00001", "PAYROLL0001", "Example Employer Ltd",
            "hr@example.org", "020 7946 0001", "1234567890", "125.5", "accountBalance");
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
        publishedGobDataEvents(); // nothing left over from an earlier test

        // one notification per share, in order; the same fixture again is the same notification again (a repeat)
        final List<EnforcementNotificationEntity> notifications = scenario.eventsInOrder().stream()
                .map(event -> awaitFinal(notifyShare(event, folder.getFileName() + "/" + event))).toList();

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
        final List<String> events = publishedGobDataEvents();
        assertPublished(expected.published(), events);
        // every share ends in a final state of its own; none is left as a stored failure, and each published event
        // belongs to exactly one PUBLISHED notification (a repeated notification is the same notification)
        assertThat(notifications).as("notifications").noneMatch(n -> n.getStatus() == NotificationStatus.STORED_FAILURE);
        // feature 003 data-model: each share's notification ends in the expected final state, in the order sent
        if (!expected.notificationStatuses().isEmpty()) {
            assertThat(notifications.stream().map(EnforcementNotificationEntity::getStatus).toList()).as("notification final states")
                    .isEqualTo(expected.notificationStatuses());
        }
        assertThat(notifications.stream().map(EnforcementNotificationEntity::getId).distinct()
                .filter(id -> notification(id).getStatus() == NotificationStatus.PUBLISHED).count())
                .as("PUBLISHED notifications").isEqualTo(events.size());
        assertEachEventBelongsToItsOwnNotification(events, notifications);
        if (expected.statuses().isEmpty()) {
            assertRow(expected);
        } else {
            assertThat(repository.findAll().stream().sorted(Comparator.comparing(HearingResultSubmissionEntity::getCreatedAt))
                    .map(HearingResultSubmissionEntity::getStatus).toList()).as("stored rows' statuses").isEqualTo(expected.statuses());
        }
        // FR-017: the fixtures' defendant PII is never logged, whatever the outcome
        Stream.concat(NEVER_LOGGED.stream(), expected.logMustNotContain().stream())
                .forEach(text -> assertThat(withoutTempDiagnostics(output.getAll())).as("log").doesNotContain(text));
        expected.logMustContain().forEach(text -> assertThat(withoutTempDiagnostics(output.getAll())).as("log").contains(text));
    }

    /**
     * Each published event carries the request ids of exactly one PUBLISHED notification of this scenario, with that
     * notification's master defendant, and no two events carry the same notification's request ids.
     */
    private void assertEachEventBelongsToItsOwnNotification(final List<String> events, final List<EnforcementNotificationEntity> notifications) {
        final List<UUID> scenarioNotifications = notifications.stream().map(EnforcementNotificationEntity::getId).toList();
        final List<UUID> owners = events.stream().map(event -> {
            final JsonNode nows = Fixtures.MAPPER.readTree(event).path("nows");
            final List<UUID> requestIds = nows.valueStream().map(now -> UUID.fromString(now.path("requestId").asString())).toList();
            final List<EnforcementNotificationNowEntity> stored = notificationNowRepository.findByRequestIdIn(requestIds);
            assertThat(stored).as("stored NOW requests of the event's request ids").hasSize(requestIds.size());
            final UUID owner = stored.getFirst().getNotificationId();
            assertThat(stored).as("one notification per event").allSatisfy(now -> assertThat(now.getNotificationId()).isEqualTo(owner));
            assertThat(scenarioNotifications).as("the event's notification is one of the scenario's").contains(owner);
            assertThat(notification(owner).getStatus()).as("the event's notification status").isEqualTo(NotificationStatus.PUBLISHED);
            nows.valueStream().forEach(now -> assertThat(now.path("masterDefendantId").asString()).as("event masterDefendantId")
                    .isEqualTo(stored.stream().filter(s -> s.getRequestId().toString().equals(now.path("requestId").asString()))
                            .findFirst().orElseThrow().getMasterDefendantId().toString()));
            return owner;
        }).toList();
        assertThat(owners).as("notifications of the published events").doesNotHaveDuplicates();
    }

    private static void assertMatches(final Path expectedRequest, final LoggedRequest post) {
        assertThat(equalToJson(readString(expectedRequest), true, false).match(post.getBodyAsString()).isExactMatch())
                .as("request equals " + expectedRequest.getFileName()).isTrue();
    }

    /** Feature 003: GOB data events published to hearing-nows; absent expectation = none. Each must satisfy hearing-nows' schema. */
    private static void assertPublished(final Published expected, final List<String> events) {
        assertThat(events).as("GOB data events").hasSize(expected == null ? 0 : expected.events());
        events.forEach(event -> assertThat(GobDataEventContract.violations(event)).as("hearing-nows event contract violations").isEmpty());
        events.forEach(event -> assertThat(Fixtures.MAPPER.readTree(event).path("_metadata").path("name").asString())
                .as("_metadata.name").isEqualTo(GOB_DATA_EVENT));
        if (expected != null && !expected.sharedTimePerEvent().isEmpty()) {
            assertThat(events.stream().map(event -> Instant.parse(Fixtures.MAPPER.readTree(event).path("sharedTime").asString())).toList())
                    .as("event sharedTime per event").isEqualTo(expected.sharedTimePerEvent().stream().map(Instant::parse).toList());
        }
        if (expected != null && !expected.gobDataReusedPerEvent().isEmpty()) {
            assertThat(events.stream().map(event -> Fixtures.MAPPER.readTree(event).path("gobDataReused").asBoolean()).toList())
                    .as("event gobDataReused per event").isEqualTo(expected.gobDataReusedPerEvent());
        }
        if (expected != null && expected.gobDataReused() != null) {
            assertThat(events).as("event gobDataReused").allSatisfy(event -> assertThat(
                    Fixtures.MAPPER.readTree(event).path("gobDataReused").asBoolean()).isEqualTo(expected.gobDataReused()));
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
     * @param event         event fixture on the test classpath, e.g. {@code events/hearing-resulted-enforcement.json}: one
     *                      share's results, notified once
     * @param events        instead of {@code event}: shares notified in order (the same fixture twice is a repeated
     *                      notification, or a first share then a reshare)
     * @param referenceData result type id (or "*") → shortCode, or {@code {"status": N}}; unlisted ids answer 404
     * @param gateway       the gateway's reply; omitted when the scenario must not reach the gateway
     */
    private record Scenario(String description, String event, List<String> events, Map<String, JsonNode> referenceData,
                            GatewayReply gateway, Expected expected) {
        Scenario {
            if ((event == null) == (events == null)) {
                throw new IllegalArgumentException("give exactly one of event and events");
            }
            referenceData = referenceData == null ? Map.of() : referenceData;
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
     * {@code published}: the GOB data events to hearing-nows (feature 003); absent = none.
     */
    private record Expected(int gatewayPosts, SubmissionStatus status, List<SubmissionStatus> statuses, Integer httpStatus,
                            String errorDetail, String errorDetailStartsWith, List<String> responsePayloadContains,
                            List<String> logMustNotContain, List<String> logMustContain, List<List<String>> resultCodesPerPost,
                            Published published, List<NotificationStatus> notificationStatuses) {
        Expected {
            notificationStatuses = notificationStatuses == null ? List.of() : notificationStatuses;
            statuses = statuses == null ? List.of() : statuses;
            resultCodesPerPost = resultCodesPerPost == null ? List.of() : resultCodesPerPost;
            responsePayloadContains = responsePayloadContains == null ? List.of() : responsePayloadContains;
            logMustNotContain = logMustNotContain == null ? List.of() : logMustNotContain;
            logMustContain = logMustContain == null ? List.of() : logMustContain;
        }
    }

    /**
     * GOB data events to hearing-nows (feature 003): how many, and their {@code gobDataReused} flag (for all of them, or per
     * event in order) and {@code sharedTime} per event (the share time of the submission whose GOB data is published).
     */
    private record Published(int events, Boolean gobDataReused, List<Boolean> gobDataReusedPerEvent, List<String> sharedTimePerEvent) {
        Published {
            gobDataReusedPerEvent = gobDataReusedPerEvent == null ? List.of() : gobDataReusedPerEvent;
            sharedTimePerEvent = sharedTimePerEvent == null ? List.of() : sharedTimePerEvent;
        }
    }
}
