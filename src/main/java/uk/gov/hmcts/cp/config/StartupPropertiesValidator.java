package uk.gov.hmcts.cp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks the connection settings when the context starts and states, once, where this pod is pointed. Same idea as
 * service-cp-crime-informant-register's {@code PropertiesValidator}.
 *
 * <p>Refuses to start without real system user ids. Results and reference data reject a call whose {@code CJSCPPUID}
 * is not a System Users member, and the GOB data event carries {@code CP_MESSAGING_SYSTEM_USER} as its user. With a
 * missing or all-zero id the service would start normally and every call would fail one at a time, so it fails at
 * startup instead, naming the setting.
 *
 * <p>The startup line carries hosts only, never whole URLs (a path or query can carry a token), never a system user id,
 * and never a credential.
 */
@Slf4j
@Component
public class StartupPropertiesValidator implements InitializingBean {

    private static final UUID NIL = new UUID(0, 0);

    private final ResultsProperties results;
    private final ClientProperties clients;
    private final String messagingSystemUser;
    private final String publicEventTopic;
    private final String brokerUrl;
    private final String datasourceUrl;

    public StartupPropertiesValidator(final ResultsProperties results, final ClientProperties clients,
                                      @Value("${cp.messaging.system-user:}") final String messagingSystemUser,
                                      @Value("${cp.messaging.public-event-topic:}") final String publicEventTopic,
                                      @Value("${spring.artemis.broker-url:}") final String brokerUrl,
                                      @Value("${spring.datasource.url:}") final String datasourceUrl) {
        this.results = results;
        this.clients = clients;
        this.messagingSystemUser = messagingSystemUser;
        this.publicEventTopic = publicEventTopic;
        this.brokerUrl = brokerUrl;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void afterPropertiesSet() {
        requireSystemUser("cp.results.cjscppuid (RESULTS_CJSCPPUID)", results.getResults().getCjscppuid());
        requireSystemUser("cp.reference-data.cjscppuid (REFERENCEDATA_CJSCPPUID)", clients.getReferenceData().getCjscppuid());
        requireSystemUser("cp.messaging.system-user (CP_MESSAGING_SYSTEM_USER)", messagingSystemUser);
        announce();
    }

    /** A system user id must be a UUID and not the all-zero placeholder. The value is never echoed. */
    /* default */ static void requireSystemUser(final String setting, final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(setting + " must be set to a System Users member id");
        }
        final UUID id;
        try {
            id = UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " must be a UUID", e);
        }
        if (NIL.equals(id)) {
            throw new IllegalStateException(setting + " is the all-zero placeholder; set it to a System Users member id");
        }
    }

    private void announce() {
        final ResultsProperties.ResultsCache cache = results.getResultsCache();
        log.info("Effective configuration. resultsHost={} referenceDataHost={} enforcementGatewayHost={} resultsCacheEnabled={}"
                        + " resultsCacheHost={} resultsCacheSsl={} brokerHost={} publicEventTopic={} databaseHost={}",
                hostOf(results.getResults().getBaseUrl()),
                hostOf(clients.getReferenceData().getBaseUrl()),
                hostOf(clients.getEnforcementGateway().getBaseUrl()),
                cache.isEnabled(),
                cache.isEnabled() ? cache.getHost() : "-",
                cache.isEnabled() ? cache.isSsl() : "-",
                hostOf(brokerUrl),
                publicEventTopic,
                hostOf(datasourceUrl.startsWith("jdbc:") ? datasourceUrl.substring("jdbc:".length()) : datasourceUrl));
    }

    /** The host of a URL, and nothing else about it; {@code ?} when it can't be read. */
    /* default */ static String hostOf(final String url) {
        Optional<String> host;
        try {
            host = Optional.ofNullable(url).filter(StringUtils::hasText).map(u -> URI.create(u.trim()).getHost());
        } catch (IllegalArgumentException e) {
            host = Optional.empty();
        }
        return host.orElse("?");
    }
}
