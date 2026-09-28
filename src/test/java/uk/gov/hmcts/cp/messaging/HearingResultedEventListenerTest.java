package uk.gov.hmcts.cp.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.service.HearingResultedProcessor;
import uk.gov.hmcts.cp.support.Fixtures;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HearingResultedEventListenerTest {

    private final HearingResultedProcessor processor = mock(HearingResultedProcessor.class);
    private final HearingResultedEventListener listener = new HearingResultedEventListener(JsonMapper.builder().build(), processor);

    @Test
    void valid_event_should_be_processed_once() throws JMSException {
        listener.onHearingResulted(message(Fixtures.json("events/hearing-resulted-enforcement.json")));

        verify(processor).process(any(HearingResultedEvent.class));
    }

    @Test
    void malformed_json_should_be_logged_not_rethrown() throws JMSException {
        final Message message = message("{not json");

        assertThatCode(() -> listener.onHearingResulted(message)).doesNotThrowAnyException();
        verifyNoInteractions(processor);
    }

    @Test
    void processing_failure_should_not_escape_the_listener() throws JMSException {
        doThrow(new IllegalStateException("boom")).when(processor).process(any());
        final Message message = message(Fixtures.json("events/hearing-resulted-enforcement.json"));

        assertThatCode(() -> listener.onHearingResulted(message)).doesNotThrowAnyException();
    }

    private static Message message(final String body) throws JMSException {
        final Message message = mock(Message.class);
        when(message.getBody(String.class)).thenReturn(body);
        when(message.getStringProperty("CPPNAME")).thenReturn("public.events.hearing.hearing-resulted");
        when(message.getJMSMessageID()).thenReturn("ID:test-1");
        return message;
    }
}
