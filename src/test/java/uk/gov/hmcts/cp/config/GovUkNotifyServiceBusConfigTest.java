package uk.gov.hmcts.cp.config;

import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R1): building the sender client opens nothing (no real Azure credential needed to
 * construct it - verified directly, matching {@link ResultsCacheConfig}'s own "creating the client opens nothing"
 * precedent); {@code onError} never throws regardless of the failure it is reporting.
 */
class GovUkNotifyServiceBusConfigTest {

    private final GovUkNotifyServiceBusConfig config = new GovUkNotifyServiceBusConfig();

    @Test
    void sender_client_should_build_without_a_real_azure_connection() {
        final GovUkNotifyProperties properties = new GovUkNotifyProperties();
        properties.setNamespace("localhost");

        final ServiceBusSenderClient client = config.govUkNotifyEmailSenderClient(properties);

        assertThat(client).isNotNull();
    }

    @Test
    void on_error_should_log_and_never_throw() {
        final ServiceBusErrorContext context = mock(ServiceBusErrorContext.class);
        when(context.getFullyQualifiedNamespace()).thenReturn("localhost");
        when(context.getException()).thenReturn(new RuntimeException("broker unreachable"));

        assertThatCode(() -> config.onError(context)).doesNotThrowAnyException();
    }
}
