package uk.gov.hmcts.cp.messaging;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * Listener container factory for {@link HearingResultedEventListener}, with its own JMS client id.
 * Each listener container opens its own connection, and a broker accepts one connection per client
 * id. Two durable listeners that both used {@code spring.jms.client-id} (this one and
 * {@link PublicEventLoggingListener}) therefore clashed ("clientID ... was already set into another
 * connection"), and the second never connected. The durable subscription is identified by this client
 * id plus {@code cp.messaging.hearing-resulted-subscription-name}. It uses the plain, not the caching,
 * connection factory, as Spring Boot does for its own listener factory.
 */
@Configuration
@Profile("docker")
public class HearingResultedJmsConfig {

    public static final String CONTAINER_FACTORY = "hearingResultedListenerContainerFactory";

    @Bean(name = CONTAINER_FACTORY)
    public DefaultJmsListenerContainerFactory hearingResultedListenerContainerFactory(
            final DefaultJmsListenerContainerFactoryConfigurer configurer,
            final ConnectionFactory connectionFactory,
            @Value("${cp.messaging.hearing-resulted-client-id}") final String clientId) {
        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setClientId(clientId);
        return factory;
    }
}
