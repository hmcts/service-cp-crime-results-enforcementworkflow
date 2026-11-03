package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.GobDataEventContract;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.onlyTempDiagnostics;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/**
 * Feature 003 US1 end to end: a notification → stage 1 (Results query, selection, GOB through the gateway, all WireMock)
 * → stage 2 → one GOB data event on the embedded broker, valid against hearing-nows' schema, carrying the notification's
 * request id and GOB's data verbatim; no PII in the logs (constitution IV).
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationFlowIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final String ENFORCEMENT = "events/hearing-resulted-enforcement.json";
    private static final String RESHARE = "events/hearing-resulted-reshare.json";
    private static final String HEARING_ID = "04180ff1-99b0-40b7-9929-ca05bdc767d8";

    @Autowired
    private JmsListenerEndpointRegistry listenerRegistry;

    @Test
    void diagnostic_listener_should_be_the_only_listener_and_connected_to_the_broker() {
        // the hearing-resulted listener is gone (feature 003): only the docker profile's public-event logger remains
        assertThat(listenerRegistry.getListenerContainers()).singleElement().satisfies(container -> await()
                .atMost(PROCESSING_TIMEOUT)
                .untilAsserted(() -> assertThat(((DefaultMessageListenerContainer) container).isRegisteredWithDestination()).isTrue()));
    }

    @Test
    void notification_should_be_sent_to_gob_once_and_published_to_hearing_nows(final CapturedOutput output) {
        publishedGobDataEvents();

        final UUID notificationId = notifyShare(ENFORCEMENT, "flow-it");
        final EnforcementNotificationEntity notification = awaitFinal(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        STUBS.verify(1, getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID)));
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        final List<String> events = publishedGobDataEvents();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(GobDataEventContract.violations(event)).isEmpty();
            final JsonNode json = Fixtures.MAPPER.readTree(event);
            assertThat(json.path("nows").get(0).path("requestId").asString())
                    .isEqualTo(UUID.nameUUIDFromBytes("flow-it".getBytes()).toString());
            assertThat(json.path("nows").get(0).path("masterDefendantId").asString()).isEqualTo(FIXTURE_MASTER_DEFENDANT_ID.toString());
            assertThat(json.path("accountNumber").asString()).isEqualTo("1234567890");
            assertThat(json.path("gobDataReused").asBoolean()).isFalse();
            assertThat(event).contains("\"nowsDataItems\":{\"accountBalance\":125.5}");
        });
        // no defendant PII and no GOB account data (account number, GOB's balance) outside the TEMP diagnostics
        assertThat(withoutTempDiagnostics(output.getAll())).contains(notificationId.toString())
                .doesNotContain("Edward", "Harrison", "2002-01-10", "QQ123456C", "1 High Street", "02079460000", "07700900123")
                .doesNotContain("1234567890", "125.5", "accountBalance", "Example Employer Ltd", "hr@example.org");
        // TEMP DIAGNOSTICS (T090): QA sees every connection point with its payload; remove with TempPayloadLog
        assertThat(onlyTempDiagnostics(output.getAll()))
                .contains("POST /enforcement-notifications received", "answered HTTP 202")
                .contains("Results query request", "Results query response for hearing " + HEARING_ID)
                .contains("Enforcement gateway request for caseUrn E012345678", "Enforcement gateway response for caseUrn E012345678")
                .contains("Stage 1 PROCESS for notification " + notificationId, "Stage 2 PUBLISH for notification " + notificationId)
                .contains("Publishing public.enforcementworkflow.enforcement-nows-data-ready")
                .contains("Edward", "accountBalance");
    }

    @Test
    void repeated_notification_should_not_reach_gob_or_hearing_nows_again() {
        publishedGobDataEvents();
        awaitFinal(notifyShare(ENFORCEMENT, "flow-it-repeat"));

        awaitFinal(notifyShare(ENFORCEMENT, "flow-it-repeat"));

        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).hasSize(1);
    }

    @Test
    void non_enforcement_share_should_end_without_gob_or_event() {
        publishedGobDataEvents();

        final EnforcementNotificationEntity notification = awaitFinal(notifyShare("events/hearing-resulted-non-enforcement.json", "flow-it-ne"));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SKIPPED_NOT_ENFORCEMENT);
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).isEmpty();
    }

    // FR-015: results not available yet are retried; once Results answers, the share is processed and published
    @Test
    void results_not_ready_then_ready_should_be_retried_and_published() {
        publishedGobDataEvents();
        STUBS.stubFor(get(urlEqualTo(RESULTS_PATH + HEARING_ID)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        final UUID notificationId = notifyShareWithoutResultsStub(ENFORCEMENT, "flow-it-retry");
        await().atMost(PROCESSING_TIMEOUT).until(() -> !STUBS.findAll(getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID))).isEmpty());

        stubResults(UUID.fromString(HEARING_ID), Fixtures.json(ENFORCEMENT));

        assertThat(awaitFinal(notificationId).getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        assertThat(STUBS.findAll(getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID)))).hasSizeGreaterThan(1);
        assertThat(publishedGobDataEvents()).hasSize(1);
    }

    // FR-015: Results down for every attempt: the share is stored for a replay, nothing reaches GOB or hearing-nows
    @Test
    void results_unavailable_for_every_retry_should_be_stored_for_a_replay() {
        publishedGobDataEvents();
        STUBS.stubFor(get(urlEqualTo(RESULTS_PATH + HEARING_ID)).willReturn(aResponse().withStatus(503)));

        final EnforcementNotificationEntity notification = awaitFinal(notifyShareWithoutResultsStub(ENFORCEMENT, "flow-it-down"));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.STORED_FAILURE);
        assertThat(notification.getFailedStage()).isEqualTo(FailedStage.PROCESS);
        assertThat(notification.getLastError()).isEqualTo("RESULTS_UNAVAILABLE");
        assertThat(notification.getNextReplayAt()).isNotNull();
        assertThat(STUBS.findAll(getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID)))).hasSize(4); // first attempt + 3 retries
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).isEmpty();
    }

    // FR-016: GOB rejects: recorded, no GOB data event, so the held NOWs stay held; never re-sent automatically
    @Test
    void gob_rejection_should_end_without_an_event_and_not_be_resent() {
        publishedGobDataEvents();
        stubGatewayStatus(502, "{\"error\":\"LIBRA_CALL_FAILED\"}");

        final EnforcementNotificationEntity notification = awaitFinal(notifyShare(ENFORCEMENT, "flow-it-rejected"));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.GOB_FAILED);
        assertThat(notification.getLastError()).isEqualTo("FAILED");
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).isEmpty();
        // the job ends: nothing left to retry or resend
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertThat(jobsRepository.count()).isZero());
    }

    // FR-008/FR-009: an amend & reshare is never sent; it publishes the earlier GOB data under its own request id
    @Test
    void reshare_should_publish_the_earlier_gob_data_for_its_own_request_id_without_contacting_gob() {
        publishedGobDataEvents();
        awaitFinal(notifyShare(ENFORCEMENT, "flow-it-first"));
        publishedGobDataEvents();

        final EnforcementNotificationEntity reshare = awaitFinal(notifyShare(RESHARE, "flow-it-reshare"));

        assertThat(reshare.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        assertThat(reshare.getGobDataReused()).isTrue();
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).singleElement().satisfies(event -> {
            final JsonNode json = Fixtures.MAPPER.readTree(event);
            assertThat(json.path("gobDataReused").asBoolean()).isTrue();
            assertThat(json.path("nows").get(0).path("requestId").asString())
                    .isEqualTo(UUID.nameUUIDFromBytes("flow-it-reshare".getBytes()).toString());
        });
    }
    // FR-009: a reshare received while the first share is still at GOB waits for it (FIRST_SHARE_IN_PROGRESS), then
    // publishes the GOB data the first share received, under its own request id; GOB is contacted once
    @Test
    void reshare_received_while_the_first_share_is_at_gob_should_wait_then_publish_the_reused_data(final CapturedOutput output) {
        publishedGobDataEvents();
        // the first share is held at GOB (below the gateway read timeout of 1000 ms) while the reshare is processed
        STUBS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(GATEWAY_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(GATEWAY_RESPONSE).withFixedDelay(700)));
        final UUID firstShare = notifyShare(ENFORCEMENT, "flow-it-wait-first");
        // the SENDING row is committed before the gateway call: from here the first share is in progress at GOB
        await().atMost(PROCESSING_TIMEOUT).until(() -> repository.findAll().stream()
                .anyMatch(row -> row.getStatus() == uk.gov.hmcts.cp.entity.SubmissionStatus.SENDING));

        final UUID reshare = notifyShare(RESHARE, "flow-it-wait-reshare");

        assertThat(awaitFinal(firstShare).getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        final EnforcementNotificationEntity reshared = awaitFinal(reshare);
        assertThat(reshared.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        assertThat(reshared.getGobDataReused()).isTrue();
        assertThat(withoutTempDiagnostics(output.getAll()))
                .contains("Notification " + reshare + ": reshare waits for the first share of hearing " + HEARING_ID);
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        final List<JsonNode> events = publishedGobDataEvents().stream().map(Fixtures.MAPPER::readTree).toList();
        assertThat(events).hasSize(2);
        assertThat(eventFor(events, "flow-it-wait-first").path("gobDataReused").asBoolean()).isFalse();
        final JsonNode reshareEvent = eventFor(events, "flow-it-wait-reshare");
        assertThat(reshareEvent.path("gobDataReused").asBoolean()).isTrue();
        assertThat(reshareEvent.path("nows")).hasSize(1);
        assertThat(reshareEvent.path("nowsDataItems").toString()).isEqualTo("{\"accountBalance\":125.5}");
    }

    // FR-012: every NOW request of the selected defendant in the notification is carried by its one event
    @Test
    void notification_with_two_now_requests_should_publish_one_event_with_both() {
        publishedGobDataEvents();

        final EnforcementNotificationEntity notification = awaitFinal(notifyShare(ENFORCEMENT, List.of("flow-it-two-1", "flow-it-two-2"),
                FIXTURE_MASTER_DEFENDANT_ID));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).singleElement().satisfies(event -> {
            final JsonNode nows = Fixtures.MAPPER.readTree(event).path("nows");
            assertThat(nows.valueStream().map(now -> now.path("requestId").asString()).toList()).containsExactlyInAnyOrder(
                    requestIdOf("flow-it-two-1").toString(), requestIdOf("flow-it-two-2").toString());
            assertThat(nows.valueStream().map(now -> now.path("masterDefendantId").asString()).distinct().toList())
                    .containsExactly(FIXTURE_MASTER_DEFENDANT_ID.toString());
        });
    }

    // FR-009, FR-011: the same first share notified again with other request ids is a new notification: GOB is not
    // contacted again; the GOB data already received is published for the new request id
    @Test
    void same_share_with_other_request_ids_should_reuse_the_gob_data_for_the_new_request() {
        publishedGobDataEvents();
        assertThat(awaitFinal(notifyShare(ENFORCEMENT, "flow-it-a")).getStatus()).isEqualTo(NotificationStatus.PUBLISHED);

        final EnforcementNotificationEntity second = awaitFinal(notifyShare(ENFORCEMENT, "flow-it-b"));

        assertThat(second.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        assertThat(second.getGobDataReused()).isTrue();
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        final List<JsonNode> events = publishedGobDataEvents().stream().map(Fixtures.MAPPER::readTree).toList();
        assertThat(events).hasSize(2);
        assertThat(eventFor(events, "flow-it-a").path("gobDataReused").asBoolean()).isFalse();
        assertThat(eventFor(events, "flow-it-b").path("gobDataReused").asBoolean()).isTrue();
    }

    // FR-012: GOB data is published only for the selected defendant's NOW requests; with none, nothing is published
    @Test
    void notification_without_a_now_request_for_the_selected_defendant_should_be_rejected_at_publish() {
        publishedGobDataEvents();

        final EnforcementNotificationEntity notification = awaitFinal(notifyShare(ENFORCEMENT, List.of("flow-it-other-defendant"),
                UUID.fromString("5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3dff")));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PUBLISH_REJECTED);
        assertThat(notification.getLastError()).isEqualTo("NO_NOW_FOR_SELECTED_DEFENDANT");
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(publishedGobDataEvents()).isEmpty();
    }

    /** The one event carrying the request id of {@code requestKey}. */
    private static JsonNode eventFor(final List<JsonNode> events, final String requestKey) {
        return events.stream().filter(event -> event.path("nows").valueStream()
                        .anyMatch(now -> now.path("requestId").asString().equals(requestIdOf(requestKey).toString())))
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), found -> {
                    assertThat(found).as("events for " + requestKey).hasSize(1);
                    return found.getFirst();
                }));
    }
}
