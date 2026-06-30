package uk.gov.hmcts.cp.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Connectivity listener: durably subscribes to the CP {@code public.event} topic and logs the name
 * ({@code CPPNAME} property) of every event received, to confirm Artemis connectivity from this
 * service. Active only under the {@code docker} profile (i.e. when deployed) — diagnostics only,
 * no filtering or processing.
 */
@Slf4j
@Component
@Profile("docker")
public class PublicEventLoggingListener {

    @JmsListener(
            destination = "${cp.messaging.public-event-topic}",
            subscription = "${cp.messaging.subscription-name}")
    public void onPublicEvent(final Message message) throws JMSException {
        log.info("Artemis public event received: name={}, jmsMessageId={}",
                message.getStringProperty("CPPNAME"), message.getJMSMessageID());
    }
}
