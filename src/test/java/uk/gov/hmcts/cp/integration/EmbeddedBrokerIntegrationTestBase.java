package uk.gov.hmcts.cp.integration;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the flow the way the results-processing Function App does (feature 003): the share's results are what the
 * Results query answers (WireMock), a notification is POSTed to {@code /enforcement-notifications}, cp-task-manager runs
 * stage 1 and stage 2, and the GOB data events published on {@code public.event} of an embedded, non-persistent Artemis
 * broker are read back. Postgres and the other stubs come from {@link WorkflowStubsIntegrationTestBase}.
 *
 * <p>Subclasses must not add their own properties or profiles: every embedded-broker test class has to share one Spring
 * context, because a second cached context would start a second in-VM broker with the same server id. The gateway read
 * timeout is shortened here, for the timeout scenario; the task poll and retry intervals for speed.
 */
// docker: the diagnostic listener; nowsmapping-test: a shortCode -> NOWS data item mapping, so requests carry nowsDataRequest
@ActiveProfiles({"docker", "nowsmapping-test"})
@TestPropertySource(properties = {
    "spring.artemis.mode=embedded",
    "spring.artemis.embedded.persistent=false",
    "spring.artemis.embedded.topics=public.event",
    "spring.jms.listener.auto-startup=true",
    "cp.enforcement-gateway.read-timeout-ms=1000",
    "cp.notification.allowed-system-users=" + EmbeddedBrokerIntegrationTestBase.FUNCTION_APP_USER,
    "cp.notification.retry-durations=1s,1s,1s",
    "job.executor.poll-interval=200"
})
public abstract class EmbeddedBrokerIntegrationTestBase extends WorkflowStubsIntegrationTestBase {

    protected static final String FUNCTION_APP_USER = "5f1a6c2e-0000-4000-8000-00000000fa01";
    protected static final String GOB_DATA_EVENT = "public.enforcementworkflow.enforcement-nows-data-ready";
    protected static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(20);
    private static final String ENFORCEMENT_AUTHORITY = "GAPGD00";
    private static final LocalDate DEFAULT_HEARING_DAY = LocalDate.parse("2026-05-03");

    private static JMSContext context;
    private static JMSConsumer gobDataEvents;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Value("${cp.messaging.public-event-topic}")
    private String publicEventTopic;

    /**
     * Notifies the workflow of one share, as the Function App does: its results become the Results query's answer, its
     * {@code isReshare} is sent in the notification (research R22), one NOW request for the Enforcement case's defendant, a request id derived from {@code requestKey} (the same key gives
     * the same id, i.e. a repeated notification). Returns the notification id.
     */
    protected UUID notifyShare(final String eventFixture, final String requestKey) {
        final JsonNode event = Fixtures.MAPPER.readTree(Fixtures.json(eventFixture));
        stubResults(UUID.fromString(event.path("hearing").path("id").asString()), Fixtures.json(eventFixture));
        return notifyShareWithoutResultsStub(eventFixture, requestKey);
    }

    /** As {@link #notifyShare}, leaving the Results query's answer to the test. */
    protected UUID notifyShareWithoutResultsStub(final String eventFixture, final String requestKey) {
        final JsonNode event = Fixtures.MAPPER.readTree(Fixtures.json(eventFixture));
        return postNotification(event, List.of(requestKey), UUID.fromString(masterDefendantOf(event)));
    }

    /**
     * As {@link #notifyShare}, with one NOW request per key, all for the given master defendant (e.g. several NOW
     * requests of the share, or a NOW request of another defendant).
     */
    protected UUID notifyShare(final String eventFixture, final List<String> requestKeys, final UUID masterDefendantId) {
        final JsonNode event = Fixtures.MAPPER.readTree(Fixtures.json(eventFixture));
        stubResults(UUID.fromString(event.path("hearing").path("id").asString()), Fixtures.json(eventFixture));
        return postNotification(event, requestKeys, masterDefendantId);
    }

    /** The request id the notification carries for {@code requestKey}. */
    protected static UUID requestIdOf(final String requestKey) {
        return UUID.nameUUIDFromBytes(requestKey.getBytes());
    }

    private UUID postNotification(final JsonNode event, final List<String> requestKeys, final UUID masterDefendantId) {
        ensureEventConsumer();
        final UUID hearingId = UUID.fromString(event.path("hearing").path("id").asString());
        final LocalDate hearingDay = event.path("hearingDay").isMissingNode() ? DEFAULT_HEARING_DAY
                : LocalDate.parse(event.path("hearingDay").asString());
        final String nows = String.join(",", requestKeys.stream().map(key -> """
                {"requestId":"%s","masterDefendantId":"%s"}""".formatted(requestIdOf(key), masterDefendantId)).toList());
        final String body = """
                {"hearingId":"%s","hearingDate":"%s","isReshare":%s,"nows":[%s]}"""
                .formatted(hearingId, hearingDay, event.path("isReshare").asBoolean(false), nows);
        try {
            final MvcResult result = mockMvc.perform(post("/enforcement-notifications")
                            .contentType(MediaType.APPLICATION_JSON).header("CJSCPPUID", FUNCTION_APP_USER).content(body))
                    .andExpect(status().isAccepted())
                    .andReturn();
            return UUID.fromString(Fixtures.MAPPER.readTree(result.getResponse().getContentAsString()).path("notificationId").asString());
        } catch (Exception e) {
            throw new IllegalStateException("notification not accepted", e);
        }
    }

    /** Waits until the notification has reached a final state (stage 2 included). */
    protected EnforcementNotificationEntity awaitFinal(final UUID notificationId) {
        await().atMost(PROCESSING_TIMEOUT).until(() -> notification(notificationId).getStatus().isFinal());
        return notification(notificationId);
    }

    /** The GOB data events published since the last call, oldest first (body text including {@code _metadata}). */
    protected List<String> publishedGobDataEvents() {
        ensureEventConsumer();
        final List<String> bodies = new ArrayList<>();
        Message message = gobDataEvents.receive(200);
        while (message != null) {
            try {
                bodies.add(((TextMessage) message).getText());
                if (!GOB_DATA_EVENT.equals(message.getStringProperty("CPPNAME"))) {
                    throw new IllegalStateException("unexpected CPPNAME " + message.getStringProperty("CPPNAME"));
                }
            } catch (JMSException e) {
                throw new IllegalStateException(e);
            }
            message = gobDataEvents.receive(200);
        }
        return bodies;
    }

    private void ensureEventConsumer() {
        synchronized (EmbeddedBrokerIntegrationTestBase.class) {
            if (gobDataEvents == null) {
                context = connectionFactory.createContext();
                gobDataEvents = context.createSharedDurableConsumer(context.createTopic(publicEventTopic), "it-gob-data-events",
                        "CPPNAME = '" + GOB_DATA_EVENT + "'");
            }
        }
    }

    /** The master defendant of the Enforcement case's first defendant, else of any defendant, else a fresh id. */
    private static String masterDefendantOf(final JsonNode event) {
        String id = null;
        for (final JsonNode prosecutionCase : event.path("hearing").path("prosecutionCases")) {
            final JsonNode defendant = prosecutionCase.path("defendants").path(0);
            if (id == null || ENFORCEMENT_AUTHORITY.equals(prosecutionCase.path("prosecutionCaseIdentifier")
                    .path("prosecutionAuthorityOUCode").asString())) {
                id = defendant.path("masterDefendantId").isMissingNode() ? id : defendant.path("masterDefendantId").asString();
            }
        }
        return id == null ? UUID.randomUUID().toString() : id;
    }
}
