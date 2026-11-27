package uk.gov.hmcts.cp.client;

import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.config.GovUkNotifyProperties;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the {@code send-email-notification} command to {@code cp-gov-uk-notify-gateway} over Azure Service Bus
 * (feature 005, contracts/gov-uk-notify-gateway-send-email.md, research.md R1): a plain {@link ServiceBusMessage}
 * whose body is the flat JSON command (per {@code command-send-email-notification.schema.json}), with
 * {@code subject=send-email-notification}, {@code ReplyTo} set to this service's own reply queue (required - a
 * command without it is dead-lettered unread), and {@code correlationId} on the message envelope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GovUkNotifyEmailSender {

    /* default */ static final String SUBJECT_SEND_EMAIL_NOTIFICATION = "send-email-notification";

    private final ServiceBusSenderClient senderClient;
    private final GovUkNotifyProperties properties;
    private final ObjectMapper objectMapper;

    /** @throws GovUkNotifyUnavailableException when the command could not be sent to the queue */
    public void sendEmail(final UUID notificationId, final NotificationType notificationType, final String sendToAddress,
                          final String fileUri) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("notificationId", notificationId.toString());
        body.put("templateId", properties.templateIdFor(notificationType).toString());
        body.put("sendToAddress", sendToAddress);
        if (fileUri != null) {
            body.put("fileUri", fileUri);
        }
        final ServiceBusMessage message = new ServiceBusMessage(objectMapper.writeValueAsString(body));
        message.setSubject(SUBJECT_SEND_EMAIL_NOTIFICATION);
        message.setReplyTo(properties.getReplyToQueue());
        message.setCorrelationId(notificationId.toString());
        try {
            senderClient.sendMessage(message);
        } catch (ServiceBusException e) {
            log.warn("send-email-notification command failed for {}: {}", notificationId, e.getClass().getSimpleName());
            throw new GovUkNotifyUnavailableException("send-email-notification command failed for " + notificationId
                    + ": " + e.getClass().getSimpleName(), e);
        }
        log.info("Sent send-email-notification command for {} ({})", notificationId, notificationType);
    }
}
