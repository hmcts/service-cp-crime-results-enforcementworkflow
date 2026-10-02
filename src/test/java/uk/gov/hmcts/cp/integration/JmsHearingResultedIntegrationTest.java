package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.GatewayContract;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.Collection;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The message-driven entry point end to end (research.md R25): a CP public event on
 * {@code public.event} → the durable, selector-filtered hearing-resulted subscription → the flow →
 * the gateway (WireMock) → a row in Postgres.
 */
@ExtendWith(OutputCaptureExtension.class)
class JmsHearingResultedIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final String ENFORCEMENT_EVENT = "events/hearing-resulted-enforcement.json";

    @Autowired
    private JmsListenerEndpointRegistry listenerRegistry;

    @Test
    void every_listener_should_be_connected_to_the_broker() {
        final Collection<MessageListenerContainer> containers = listenerRegistry.getListenerContainers();

        // the diagnostics listener and the hearing-resulted listener, each with its own client id (R23)
        assertThat(containers).hasSize(2);
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertThat(containers).allSatisfy(container ->
                assertThat(((DefaultMessageListenerContainer) container).isRegisteredWithDestination()).isTrue()));
    }

    @Test
    void hearing_resulted_event_should_be_submitted_once_and_the_reply_stored(final CapturedOutput output) {
        publish(HEARING_RESULTED, Fixtures.json(ENFORCEMENT_EVENT));
        awaitProcessed();

        final HearingResultSubmissionEntity row = repository.findAll().getFirst();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getResponsePayload()).contains("accountBalance");

        assertThat(STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH)))).singleElement().satisfies(post -> {
            assertThat(GatewayContract.hearingResultedRequestViolations(post.getBodyAsString())).isEmpty();
            assertThat(LibraContract.hearingResultedRequestViolations(post.getBodyAsString())).isEmpty();
        });
        // what QA looks for: the event arrived, and what was sent; ids, caseUrn and codes only
        assertThat(output.getAll()).contains("Hearing resulted event received: jmsMessageId=ID:")
                .contains("caseUrn E012345678: sending to the enforcement gateway (results [SC, DW], NOWS data items 3)");
        // FR-017: defendant PII must not reach the logs on the JMS path either
        assertThat(output.getAll()).doesNotContain("Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street");
    }

    @Test
    void event_with_another_cpp_name_should_not_be_consumed_by_the_hearing_resulted_listener() {
        // a fresh hearing id: had this been consumed, it would have left a row of its own
        publish("public.events.hearing.hearing-confirmed",
                Fixtures.json(ENFORCEMENT_EVENT).replace(FIXTURE_HEARING_ID, UUID.randomUUID().toString()));
        awaitProcessed();

        assertThat(repository.count()).isZero();
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void malformed_message_should_be_logged_without_its_body_and_not_stop_the_next_event(final CapturedOutput output) {
        publish(HEARING_RESULTED, "{\"hearing\": not-json Edward Harrison");
        publish(HEARING_RESULTED, Fixtures.json(ENFORCEMENT_EVENT));
        awaitProcessed();

        assertThat(repository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED));
        assertThat(output.getAll())
                .contains("Failed to process " + HEARING_RESULTED + " event")
                .doesNotContain("not-json", "Edward");
    }

    // a non-text message is logged and dropped, not rolled back and redelivered (constitution V)
    @Test
    void non_text_message_should_be_logged_once_and_not_stop_the_next_event(final CapturedOutput output) {
        jmsTemplate.send("public.event", session -> {
            final jakarta.jms.BytesMessage message = session.createBytesMessage();
            message.writeBytes(Fixtures.json(ENFORCEMENT_EVENT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setStringProperty("CPPNAME", HEARING_RESULTED);
            return message;
        });
        publish(HEARING_RESULTED, Fixtures.json(ENFORCEMENT_EVENT));
        awaitProcessed();

        assertThat(repository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED));
        assertThat(output.getAll().split("Failed to process " + HEARING_RESULTED + " event", -1)).hasSize(2);
        assertThat(output.getAll()).contains("MessageFormatException");
    }
}
