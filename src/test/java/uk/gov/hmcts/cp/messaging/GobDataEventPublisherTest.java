package uk.gov.hmcts.cp.messaging;

import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.service.NowsHandoverPayloadBuilder;
import uk.gov.hmcts.cp.service.NowsHandoverPayloadBuilder.NowRequest;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.GobDataEventContract;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 003 (FR-012, research R12): the GOB data event on the CP public topic, in the CPP envelope. */
class GobDataEventPublisherTest {

    private static final String EVENT = "public.enforcementworkflow.enforcement-nows-data-ready";
    private static final String SYSTEM_USER = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private final JmsTemplate jmsTemplate = mock(JmsTemplate.class);
    private final GobDataEventPublisher publisher = new GobDataEventPublisher(jmsTemplate, "jms.topic.public.event", EVENT, SYSTEM_USER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void event_should_be_a_cpp_envelope_with_cppname_on_the_configured_topic() throws Exception {
        final ObjectNode payload = new NowsHandoverPayloadBuilder().build(UUID.randomUUID(),
                List.of(new NowRequest(UUID.randomUUID(), UUID.randomUUID())), "26000123A",
                NowsHandoverPayloadBuilder.nowsDataItemsOf("{\"nowsDataItems\":{\"accountBalance\":125.50}}").orElseThrow(), false, NOW);
        final Session session = mock(Session.class);
        final TextMessage message = mock(TextMessage.class);
        when(session.createTextMessage(anyString())).thenReturn(message);

        final UUID id = publisher.publish(payload);

        final ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(jmsTemplate).send(eq("jms.topic.public.event"), creator.capture());
        creator.getValue().createMessage(session);
        verify(message).setStringProperty("CPPNAME", EVENT);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(session).createTextMessage(body.capture());
        final JsonNode envelope = Fixtures.MAPPER.readTree(body.getValue());
        assertThat(envelope.path("_metadata").path("id").asString()).isEqualTo(id.toString());
        assertThat(envelope.path("_metadata").path("name").asString()).isEqualTo(EVENT);
        assertThat(envelope.path("_metadata").path("createdAt").asString()).isEqualTo(NOW.toString());
        assertThat(envelope.path("_metadata").path("context").path("user").asString()).isEqualTo(SYSTEM_USER);
        assertThat(body.getValue()).contains("\"accountBalance\":125.50");
        assertThat(GobDataEventContract.violations(body.getValue())).isEmpty();
    }

    // the broker failure reaches the stage, which retries and then stores it (FR-015); nothing is swallowed
    @Test
    void broker_failure_should_be_rethrown_unchanged() {
        final ObjectNode payload = new NowsHandoverPayloadBuilder().build(UUID.randomUUID(),
                List.of(new NowRequest(UUID.randomUUID(), UUID.randomUUID())), "26000123A",
                NowsHandoverPayloadBuilder.nowsDataItemsOf("{\"nowsDataItems\":{}}").orElseThrow(), false, NOW);
        final org.springframework.jms.UncategorizedJmsException failure = new org.springframework.jms.UncategorizedJmsException("broker down");
        org.mockito.Mockito.doThrow(failure).when(jmsTemplate).send(anyString(), any(MessageCreator.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publisher.publish(payload)).isSameAs(failure);
    }
}
