package uk.gov.hmcts.cp.messaging;

import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.service.DocGeneratorEventListener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R2, plan.md D8): the single broadened-selector subscription delegates
 * systemdocgenerator events to {@link DocGeneratorEventListener}, never for results events (feature 003's own
 * diagnostic-only concern).
 */
class PublicEventLoggingListenerTest {

    private final DocGeneratorEventListener docGeneratorEventListener = mock(DocGeneratorEventListener.class);
    private final PublicEventLoggingListener listener = new PublicEventLoggingListener(docGeneratorEventListener);

    @Test
    void results_event_should_be_logged_only_never_delegated() throws Exception {
        final TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty("CPPNAME")).thenReturn("public.results.police-result-generated");
        when(message.getJMSMessageID()).thenReturn("msg-1");

        listener.onPublicEvent(message);

        verify(docGeneratorEventListener, never()).handle(any(), any());
    }

    @Test
    void systemdocgenerator_event_should_be_delegated() throws Exception {
        final TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty("CPPNAME")).thenReturn("public.systemdocgenerator.events.document-available");
        when(message.getJMSMessageID()).thenReturn("msg-2");
        when(message.getText()).thenReturn("{\"sourceCorrelationId\":\"x\"}");

        listener.onPublicEvent(message);

        verify(docGeneratorEventListener).handle("public.systemdocgenerator.events.document-available",
                "{\"sourceCorrelationId\":\"x\"}");
    }

    @Test
    void message_with_no_cppname_should_not_be_delegated() throws Exception {
        final TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty("CPPNAME")).thenReturn(null);
        when(message.getJMSMessageID()).thenReturn("msg-4");

        listener.onPublicEvent(message);

        verify(docGeneratorEventListener, never()).handle(any(), any());
    }

    @Test
    void non_text_message_should_not_be_delegated() throws Exception {
        final Message message = mock(Message.class);
        when(message.getStringProperty("CPPNAME")).thenReturn("public.systemdocgenerator.events.document-available");
        when(message.getJMSMessageID()).thenReturn("msg-3");

        listener.onPublicEvent(message);

        verify(docGeneratorEventListener, never()).handle(any(), any());
    }
}
