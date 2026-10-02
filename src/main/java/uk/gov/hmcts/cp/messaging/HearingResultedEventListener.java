package uk.gov.hmcts.cp.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.service.HearingResultedProcessor;

/**
 * Consumes {@code public.events.hearing.hearing-resulted} on its own durable subscription, separate
 * from {@link PublicEventLoggingListener}'s diagnostics one. It hands each event to
 * {@link HearingResultedProcessor}. Active only under the {@code docker} profile, so it never tries
 * to reach a broker in tests.
 */
@Slf4j
@Component
@Profile("docker")
@RequiredArgsConstructor
public class HearingResultedEventListener {

    private final ObjectMapper objectMapper;
    private final HearingResultedProcessor processor;

    @JmsListener(
            destination = "${cp.messaging.public-event-topic}",
            subscription = "${cp.messaging.hearing-resulted-subscription-name}",
            selector = "${cp.messaging.hearing-resulted-selector}",
            containerFactory = HearingResultedJmsConfig.CONTAINER_FACTORY)
    public void onHearingResulted(final Message message) throws JMSException {
        // message id only: the body carries defendant PII; the processor logs the hearing id and outcome
        log.info("Hearing resulted event received: jmsMessageId={}", message.getJMSMessageID());
        try {
            processor.process(objectMapper.readValue(message.getBody(String.class), HearingResultedEvent.class));
            // deliberately broad: a failure on one message must not kill this listener thread (constitution Principle V).
            // JMSException included: a non-text message would otherwise roll back and be redelivered.
        } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") final RuntimeException | JMSException e) {
            log.error("Failed to process {} event (jmsMessageId={}): {}", message.getStringProperty("CPPNAME"),
                    message.getJMSMessageID(), e.getClass().getSimpleName());
        }
    }
}
