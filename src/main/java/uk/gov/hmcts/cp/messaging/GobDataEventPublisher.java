package uk.gov.hmcts.cp.messaging;

import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.service.NowsHandoverPayloadBuilder;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes the GOB data event to hearing-nows on the CP public topic (feature 003, FR-012, research R12): a
 * persistent JMS text message holding the CPP envelope, with the {@code CPPNAME} property set to the event name.
 * The topic is configurable (AKS {@code public.event}; the local docker broker {@code jms.topic.public.event}). The
 * message holds GOB data: never logged; logs carry the message id only.
 */
@Slf4j
@Component
public class GobDataEventPublisher {

    private final JmsTemplate jmsTemplate;
    private final String topic;
    private final String eventName;
    private final String systemUser;
    private final Clock clock;

    public GobDataEventPublisher(final JmsTemplate jmsTemplate,
                                 @Value("${cp.messaging.public-event-topic}") final String topic,
                                 @Value("${cp.messaging.gob-data-event-name}") final String eventName,
                                 @Value("${cp.messaging.system-user}") final String systemUser,
                                 final Clock clock) {
        this.jmsTemplate = jmsTemplate;
        this.topic = topic;
        this.eventName = eventName;
        this.systemUser = systemUser;
        this.clock = clock;
    }

    /** @return the envelope's {@code _metadata.id} */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // TEMP DIAGNOSTICS: logged and rethrown unchanged
    public UUID publish(final ObjectNode payload) {
        final UUID id = UUID.randomUUID();
        final String body = NowsHandoverPayloadBuilder.write(CppEnvelope.wrap(eventName, systemUser, payload, id, Instant.now(clock)));
        // TEMP DIAGNOSTICS: destination, CPPNAME and the full message
        TempPayloadLog.info("Publishing {} message {} to {} (CPPNAME={}): body={}", eventName, id, topic, eventName, body);
        try {
            jmsTemplate.send(topic, session -> {
                final TextMessage message = session.createTextMessage(body);
                message.setStringProperty(CppEnvelope.CPPNAME, eventName);
                return message;
            });
        } catch (RuntimeException e) {
            // TEMP DIAGNOSTICS: message, root cause and stack trace; rethrown unchanged
            TempPayloadLog.error("Publishing {} message {} to {} failed: {} - {} (rootCause={})", eventName, id, topic,
                    e.getClass().getSimpleName(), e.getMessage(), NestedExceptionUtils.getMostSpecificCause(e).toString(), e);
            throw e;
        }
        log.info("Published {} message {} to {}", eventName, id, topic);
        return id;
    }
}
