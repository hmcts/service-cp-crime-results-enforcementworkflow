package uk.gov.hmcts.cp.client;

import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.config.GovUkNotifyProperties;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GovUkNotifyEmailSenderTest {

    private static final UUID NOTIFICATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ServiceBusSenderClient senderClient = mock(ServiceBusSenderClient.class);
    private GovUkNotifyEmailSender sender;

    @BeforeEach
    void setUp() {
        final GovUkNotifyProperties properties = new GovUkNotifyProperties();
        properties.setReplyToQueue("enforcementworkflow-notification-result");
        properties.setTemplateIds(java.util.Map.of(NotificationType.FIRST_SHARE, TEMPLATE_ID));
        sender = new GovUkNotifyEmailSender(senderClient, properties, JsonMapper.builder().build());
    }

    @Test
    void should_send_a_message_with_subject_reply_to_and_correlation_id() {
        sender.sendEmail(NOTIFICATION_ID, NotificationType.FIRST_SHARE, "team@example.gov.uk",
                "https://account.blob.core.windows.net/result-notification/documents/" + NOTIFICATION_ID + ".pdf");

        final ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(senderClient).sendMessage(captor.capture());
        final ServiceBusMessage message = captor.getValue();

        assertThat(message.getSubject()).isEqualTo("send-email-notification");
        assertThat(message.getReplyTo()).isEqualTo("enforcementworkflow-notification-result");
        assertThat(message.getCorrelationId()).isEqualTo(NOTIFICATION_ID.toString());
        assertThat(message.getBody().toString())
                .contains("\"notificationId\":\"" + NOTIFICATION_ID + "\"")
                .contains("\"templateId\":\"" + TEMPLATE_ID + "\"")
                .contains("\"sendToAddress\":\"team@example.gov.uk\"")
                .contains("\"fileUri\":\"https://account.blob.core.windows.net");
    }

    @Test
    void should_omit_file_uri_when_not_yet_available() {
        sender.sendEmail(NOTIFICATION_ID, NotificationType.FIRST_SHARE, "team@example.gov.uk", null);

        final ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(senderClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getBody().toString()).doesNotContain("fileUri");
    }

    @Test
    void sender_failure_should_throw_unavailable() {
        doThrow(new ServiceBusException(new RuntimeException("broker down"), ServiceBusErrorSource.SEND))
                .when(senderClient).sendMessage(any());

        assertThatThrownBy(() -> sender.sendEmail(NOTIFICATION_ID, NotificationType.FIRST_SHARE, "team@example.gov.uk", null))
                .isInstanceOf(GovUkNotifyUnavailableException.class);
    }
}
