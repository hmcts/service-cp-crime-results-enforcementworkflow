package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.repository.EnforcementNotificationNowRepository;
import uk.gov.hmcts.cp.repository.EnforcementNotificationRepository;
import uk.gov.hmcts.cp.repository.GobSentOffenceRepository;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;
import uk.gov.hmcts.cp.service.HearingResultedProcessor;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.taskmanager.persistence.repository.JobsRepository;

import java.time.LocalDate;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Real Postgres, with WireMock standing in for reference data, the enforcement gateway and the Results query (feature
 * 003). The enforcement fixture's results resolve to SC and WC. {@link #process(String)} runs stage 1's processing for a
 * fixture under a new notification, as the notification task does once it has the share's results.
 */
// local/test stand-in for paymentDueDate (research.md R13); the application default is NONE. Subclasses may override.
@TestPropertySource(properties = "cp.hearing-result.payment-due-date-fallback=HEARING_DATE")
public abstract class WorkflowStubsIntegrationTestBase extends IntegrationTestBase {

    protected static final WireMockServer STUBS = new WireMockServer(wireMockConfig().dynamicPort());
    protected static final String GATEWAY_PATH = "/hearingResulted";
    protected static final String GATEWAY_RESPONSE = """
            {"caseUrn":"E012345678","timestamp":"2026-05-03T14:30:00Z","correlationId":"9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c",\
            "nowsDataItems":{"accountBalance":125.5}}""";
    protected static final String DEFINITIONS = "/referencedata-query-api/query/api/rest/referencedata/result-definitions/";
    protected static final String RESULTS_PATH = "/results-query-api/query/api/rest/results/hearingDetails/internal/";
    /** The master defendant id of the enforcement fixture's defendant (distinct from its defendant id, so a mix-up fails). */
    protected static final UUID FIXTURE_MASTER_DEFENDANT_ID = UUID.fromString("5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70");
    private static final JsonMapper RESULTS_MAPPER = JsonMapper.builder().build();

    static {
        STUBS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(STUBS::stop));
    }

    @Autowired
    protected HearingResultedProcessor processor;

    @Autowired
    protected HearingResultSubmissionRepository repository;

    @Autowired
    protected GobSentOffenceRepository sentOffenceRepository;

    @Autowired
    protected EnforcementNotificationRepository notificationRepository;

    @Autowired
    protected EnforcementNotificationNowRepository notificationNowRepository;

    @Autowired
    protected JobsRepository jobsRepository;

    @DynamicPropertySource
    static void stubProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.reference-data.base-url", STUBS::baseUrl);
        registry.add("cp.enforcement-gateway.base-url", STUBS::baseUrl);
        registry.add("cp.results.base-url", STUBS::baseUrl);
    }

    @BeforeEach
    void stubReferenceDataAndGateway() {
        // also cleared before each test: a run stopped mid-test (IDE stop, killed JVM) skips @AfterEach, and the
        // fixtures share hearing/case/defendant ids, so a leftover row would make the next run "already submitted"
        // the fixtures also share offence ids: offences recorded as sent would make the next test's share "nothing new"
        deleteRows();
        stubShortCode("11111111-1111-1111-1111-111111111111", "SC");
        stubShortCode("22222222-2222-2222-2222-222222222222", "WC");
        stubGateway(GATEWAY_RESPONSE);
    }

    @AfterEach
    void resetStubsAndRows() {
        STUBS.resetAll();
        deleteRows();
    }

    protected void deleteRows() {
        jobsRepository.deleteAll();
        notificationNowRepository.deleteAll();
        notificationRepository.deleteAll();
        sentOffenceRepository.deleteAll();
        repository.deleteAll();
    }

    /** Stage 1's processing of a fixture under a new notification (one NOW request for the fixture's defendant). */
    protected UUID process(final String fixture) {
        final HearingResultedEvent event = Fixtures.event(fixture);
        final UUID notificationId = newNotification(event.hearing() == null ? UUID.randomUUID() : event.hearing().id(),
                event.hearingDay() == null ? LocalDate.parse("2026-05-03") : event.hearingDay());
        processor.process(event, notificationId);
        return notificationId;
    }

    /** A stored notification in PROCESSING, as the notification task leaves it before processing. */
    protected UUID newNotification(final UUID hearingId, final LocalDate hearingDate) {
        final EnforcementNotificationEntity notification = new EnforcementNotificationEntity();
        notification.setId(UUID.randomUUID());
        notification.setHearingId(hearingId);
        notification.setHearingDate(hearingDate);
        notification.setCaller(UUID.randomUUID().toString());
        notification.setStatus(NotificationStatus.PROCESSING);
        notificationRepository.saveAndFlush(notification);
        notificationNowRepository.saveAndFlush(new EnforcementNotificationNowEntity(UUID.randomUUID(), notification.getId(),
                FIXTURE_MASTER_DEFENDANT_ID));
        return notification.getId();
    }

    protected EnforcementNotificationEntity notification(final UUID id) {
        return notificationRepository.findById(id).orElseThrow();
    }

    /**
     * The Results query answers with this share's results: the event fixture reduced to what the real query returns,
     * {@code hearing} and {@code sharedTime} (research F8; no {@code isReshare}, no {@code hearingDay}).
     */
    protected static void stubResults(final UUID hearingId, final String eventJson) {
        final ObjectNode event = (ObjectNode) RESULTS_MAPPER.readTree(eventJson);
        final ObjectNode details = RESULTS_MAPPER.createObjectNode();
        details.set("hearing", event.get("hearing"));
        if (event.has("sharedTime")) {
            details.set("sharedTime", event.get("sharedTime"));
        }
        STUBS.stubFor(get(urlEqualTo(RESULTS_PATH + hearingId))
                .withHeader("Accept", equalTo("application/vnd.results.hearing-details-internal+json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(details.toString())));
    }

    protected static void stubGatewayStatus(final int status, final String responseBody) {
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    protected static void stubGateway(final String responseBody) {
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    protected static void stubShortCode(final String definitionId, final String shortCode) {
        stubShortCode(definitionId, shortCode, "2026-05-03");
    }

    /** As above, for a lookup on another hearing day (a later share). */
    protected static void stubShortCode(final String definitionId, final String shortCode, final String on) {
        STUBS.stubFor(get(urlPathEqualTo(DEFINITIONS + definitionId))
                .withQueryParam("on", equalTo(on))
                .withHeader("Accept", equalTo("application/vnd.referencedata.get-result-definition+json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.referencedata.get-result-definition+json")
                        .withBody("{\"id\":\"" + definitionId + "\",\"shortCode\":\"" + shortCode + "\"}")));
    }
}
