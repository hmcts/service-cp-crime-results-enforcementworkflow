package uk.gov.hmcts.cp.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.service.ResultNotificationReplyListener;

/**
 * Azure Service Bus wiring for {@code cp-gov-uk-notify-gateway} dispatch (feature 005, research.md R1). Workload
 * Identity ({@code DefaultAzureCredential}) only - no connection strings in any real environment. The sender client
 * is lazy (building it does not open a connection), so it is always available; the reply processor actively opens a
 * connection on {@code start()}, so - mirroring {@code PublicEventLoggingListener}'s {@code docker}-profile gating -
 * it only runs when deployed. Integration tests exercise {@link ResultNotificationReplyListener}'s own
 * {@code onMessage}/{@code handle} directly instead of a real Service Bus connection (research.md R12: no embedded
 * broker equivalent exists for Azure Service Bus in this repo).
 */
@Slf4j
@Configuration
public class GovUkNotifyServiceBusConfig {

    @Bean
    public ServiceBusSenderClient govUkNotifyEmailSenderClient(final GovUkNotifyProperties properties) {
        return new ServiceBusClientBuilder()
                .fullyQualifiedNamespace(properties.getNamespace())
                .credential(new DefaultAzureCredentialBuilder().build())
                .sender()
                .queueName(properties.getSendEmailQueue())
                .buildClient();
    }

    @Profile("docker")
    @Bean(initMethod = "start", destroyMethod = "close")
    public ServiceBusProcessorClient govUkNotifyReplyProcessorClient(final GovUkNotifyProperties properties,
                                                                      final ResultNotificationReplyListener listener) {
        return new ServiceBusClientBuilder()
                .fullyQualifiedNamespace(properties.getNamespace())
                .credential(new DefaultAzureCredentialBuilder().build())
                .processor()
                .queueName(properties.getReplyToQueue())
                .processMessage(listener::onMessage)
                .processError(this::onError)
                .buildProcessorClient();
    }

    /* default */ void onError(final ServiceBusErrorContext context) {
        log.error("Error on {} reply queue: {}", context.getFullyQualifiedNamespace(),
                context.getException().getClass().getSimpleName());
    }
}
