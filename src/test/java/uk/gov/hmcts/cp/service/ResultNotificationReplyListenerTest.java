package uk.gov.hmcts.cp.service;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R1): bare ASB subject values - not public.notificationnotify.events.* (a legacy-naming
 * correction). notification-validation-failed carries no notificationId (the command was never accepted) and
 * correlates through its own echoed originalEventPayload.
 */
class ResultNotificationReplyListenerTest {

    private static final UUID ID = UUID.randomUUID();

    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final NotificationStore triggerStore = mock(NotificationStore.class);
    private final ResultNotificationBlobClient blobClient = mock(ResultNotificationBlobClient.class);
    private final ResultNotificationReplyListener listener = new ResultNotificationReplyListener(store, triggerStore, blobClient,
            JsonMapper.builder().build());

    @Test
    void notification_sent_should_mark_sent_then_purge_both_blobs() {
        when(store.find(ID)).thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        listener.handle("notification-sent", "{\"notificationId\":\"" + ID + "\",\"sentTime\":\"2026-11-24T10:00:00Z\"}");

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(store, blobClient);
        order.verify(store).markSent(ID);
        order.verify(blobClient).purge(ID);
        order.verify(store).markPurged(ID);
    }

    @Test
    void notification_sent_for_an_unknown_row_should_be_ignored() {
        when(store.find(ID)).thenReturn(Optional.empty());

        listener.handle("notification-sent", "{\"notificationId\":\"" + ID + "\",\"sentTime\":\"2026-11-24T10:00:00Z\"}");

        verify(store, never()).markSent(any());
        verify(blobClient, never()).purge(any());
    }

    @Test
    void notification_failed_should_mark_email_failed_with_the_error_message() {
        listener.handle("notification-failed", "{\"notificationId\":\"" + ID
                + "\",\"failedTime\":\"2026-11-24T10:00:00Z\",\"errorMessage\":\"TEMPLATE_RETIRED\"}");

        verify(store).markEmailFailed(ID, "TEMPLATE_RETIRED");
    }

    // notification-validation-failed has no notificationId of its own - correlates via its echoed originalEventPayload
    @Test
    void notification_validation_failed_should_correlate_via_the_original_event_payload() {
        final String originalPayload = "{\"notificationId\":\"" + ID + "\",\"templateId\":\"t\",\"sendToAddress\":\"a@b.com\"}";
        final String body = "{\"reason\":\"PAYLOAD_VALIDATION_FAILED\",\"details\":\"sendToAddress: must not be blank\","
                + "\"originalEventPayload\":" + JsonMapper.builder().build().writeValueAsString(originalPayload) + "}";

        listener.handle("notification-validation-failed", body);

        verify(store).markEmailFailed(ID, "PAYLOAD_VALIDATION_FAILED: sendToAddress: must not be blank");
    }

    @Test
    void notification_validation_failed_with_no_notification_id_in_the_original_payload_should_not_throw() {
        final String body = "{\"reason\":\"PAYLOAD_VALIDATION_FAILED\",\"details\":\"body unreadable\","
                + "\"originalEventPayload\":\"{}\"}";

        listener.handle("notification-validation-failed", body);

        verify(store, never()).markEmailFailed(any(), any());
    }

    @Test
    void notification_validation_failed_with_an_unreadable_original_payload_should_not_throw() {
        final String body = "{\"reason\":\"PAYLOAD_VALIDATION_FAILED\",\"details\":\"not json\",\"originalEventPayload\":\"not-json\"}";

        listener.handle("notification-validation-failed", body);

        verify(store, never()).markEmailFailed(any(), any());
    }

    @Test
    void unrecognised_subject_should_be_ignored() {
        listener.handle("some-other-subject", "{}");

        verify(store, never()).markSent(any());
        verify(store, never()).markEmailFailed(any(), any());
    }

    @Test
    void on_message_should_parse_the_subject_and_body_then_complete() {
        final ServiceBusReceivedMessageContext context = mock(ServiceBusReceivedMessageContext.class);
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(context.getMessage()).thenReturn(message);
        when(message.getSubject()).thenReturn("notification-failed");
        when(message.getBody()).thenReturn(BinaryData.fromString("{\"notificationId\":\"" + ID
                + "\",\"failedTime\":\"2026-11-24T10:00:00Z\",\"errorMessage\":\"X\"}"));

        listener.onMessage(context);

        verify(store).markEmailFailed(ID, "X");
        verify(context).complete();
    }
}
