package uk.gov.hmcts.cp.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.service.DocGeneratorEventListener;

/**
 * The single durable subscription this service holds on the CP {@code public.event} topic, selector-filtered to
 * results public events (e.g. {@code public.results.police-result-generated}, logged only, connectivity
 * diagnostics) and - additively, feature 005 (research.md R2, plan.md D8) - the doc generator's own completion
 * events, delegated to {@link DocGeneratorEventListener}. A broadened selector on this one existing subscription,
 * not a second durable subscription: {@code spring.jms.client-id} is a single, service-wide id, and a second
 * {@code @JmsListener} container attempting its own connection with that same id fails
 * ({@code InvalidClientIDException}) - confirmed empirically, not merely a style choice. Active only under the
 * {@code docker} profile (i.e. when deployed).
 */
@Slf4j
@Component
@Profile("docker")
@RequiredArgsConstructor
public class PublicEventLoggingListener {

    private static final String CPPNAME_SYSTEMDOCGENERATOR_PREFIX = "public.systemdocgenerator.events.";

    private final DocGeneratorEventListener docGeneratorEventListener;

    @JmsListener(
            destination = "${cp.messaging.public-event-topic}",
            subscription = "${cp.messaging.subscription-name}",
            selector = "${cp.messaging.selector}")
    public void onPublicEvent(final Message message) throws JMSException {
        final String cppname = message.getStringProperty("CPPNAME");
        log.info("Artemis public event received: name={}, jmsMessageId={}", cppname, message.getJMSMessageID());
        if (cppname != null && cppname.startsWith(CPPNAME_SYSTEMDOCGENERATOR_PREFIX) && message instanceof TextMessage textMessage) {
            docGeneratorEventListener.handle(cppname, textMessage.getText());
        }
    }
}
