package uk.gov.hmcts.cp.integration;

import jakarta.jms.TextMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Drives the flow through JMS, the way CP does: a message on {@code public.event} with a
 * {@code CPPNAME} property, consumed by the real listeners (the {@code docker} profile) from an
 * embedded, non-persistent Artemis broker (research.md R25). Postgres and the WireMock stubs come
 * from {@link WorkflowStubsIntegrationTestBase}.
 *
 * <p>Subclasses must not add their own properties or profiles: every embedded-broker test class has to
 * share one Spring context, because a second cached context would start a second in-VM broker with
 * the same server id. The gateway read timeout is shortened here, for the timeout scenario.
 */
// docker: the real listeners; nowsmapping-test: a shortCode -> NOWS data item mapping, so requests carry nowsDataRequest
@ActiveProfiles({"docker", "nowsmapping-test"})
@TestPropertySource(properties = {
    "spring.artemis.mode=embedded",
    "spring.artemis.embedded.persistent=false",
    "spring.artemis.embedded.topics=public.event",
    "spring.jms.listener.auto-startup=true",
    "cp.enforcement-gateway.read-timeout-ms=1000"
})
public abstract class EmbeddedBrokerIntegrationTestBase extends WorkflowStubsIntegrationTestBase {

    protected static final String HEARING_RESULTED = "public.events.hearing.hearing-resulted";
    /** The hearing id every event fixture shares. */
    protected static final String FIXTURE_HEARING_ID = "04180ff1-99b0-40b7-9929-ca05bdc767d8";
    /** The unknown result type in hearing-resulted-unknown-codes-only.json, replaced in the marker event. */
    private static final String UNKNOWN_RESULT_TYPE_ID = "33333333-3333-3333-3333-333333333333";
    protected static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(15);
    /** Longer than the gateway read timeout above, so a stubbed reply with this delay times out. */
    protected static final int SLOWER_THAN_READ_TIMEOUT_MS = 2500;

    @Autowired
    protected JmsTemplate jmsTemplate;

    @Value("${cp.messaging.public-event-topic}")
    private String publicEventTopic;

    /** Publishes a CP public event: a text message with the event name in {@code CPPNAME}. */
    protected void publish(final String cppName, final String body) {
        jmsTemplate.send(publicEventTopic, session -> {
            final TextMessage message = session.createTextMessage(body);
            message.setStringProperty("CPPNAME", cppName);
            return message;
        });
    }

    /**
     * Waits until every message published so far has been fully processed. The listener has a single
     * consumer, so it publishes a marker event (one result type no stub knows, under a fresh hearing id, so
     * it never reaches the gateway) and waits for the marker's final row, then deletes that row. This also
     * proves that a message expected to leave no trace was consumed, rather than still being in flight.
     */
    protected void awaitProcessed() {
        final UUID marker = UUID.randomUUID();
        publish(HEARING_RESULTED, Fixtures.json("events/hearing-resulted-unknown-codes-only.json")
                .replace(FIXTURE_HEARING_ID, marker.toString())
                .replace(UNKNOWN_RESULT_TYPE_ID, UUID.randomUUID().toString()));
        await().atMost(PROCESSING_TIMEOUT).until(() -> markerRow(marker).filter(row -> row.getStatus().isFinal()).isPresent());
        markerRow(marker).ifPresent(repository::delete);
    }

    private Optional<HearingResultSubmissionEntity> markerRow(final UUID marker) {
        return repository.findAll().stream().filter(row -> marker.equals(row.getHearingId())).findFirst();
    }
}
