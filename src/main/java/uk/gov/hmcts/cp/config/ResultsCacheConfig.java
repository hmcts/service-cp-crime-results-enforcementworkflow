package uk.gov.hmcts.cp.config;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.cp.client.ResultsCacheClient;

/**
 * The Results cache (feature 003, research R8), wired the way service-cp-crime-informant-register wires its payload
 * cache: a Lettuce {@link RedisClient} built from {@code cp.results-cache.*}, and the client that reads through it. Only
 * when {@code cp.results-cache.enabled=true}; otherwise none of these beans exist and stage 1 reads the Results query.
 *
 * <p>One difference from the informant register, on purpose: no access key. Every connection authenticates with a
 * Microsoft Entra ID token for this service's managed identity (CLAUDE.md, Azure SDK via Managed Identity), so no key
 * exists in config, env vars or Helm values.
 *
 * <p>Creating the client opens nothing: the connection is made on the first read, so a cache that is down cannot stop
 * the service from starting.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cp.results-cache.enabled", havingValue = "true")
public class ResultsCacheConfig {

    /* default */ static final String REDIS_SCOPE = "https://redis.azure.com/.default";

    @Bean
    public TokenCredential resultsCacheCredential(final ResultsProperties properties) {
        final String clientId = properties.getResultsCache().getManagedIdentityClientId();
        return StringUtils.hasText(clientId)
                ? new ManagedIdentityCredentialBuilder().clientId(clientId).build()
                : new DefaultAzureCredentialBuilder().build();
    }

    /** The Lettuce client for the Results cache; Spring shuts it down (event loops, connections) on context close. */
    @Bean(destroyMethod = "shutdown")
    public RedisClient resultsCacheRedisClient(final ResultsProperties properties, final TokenCredential resultsCacheCredential) {
        final ResultsProperties.ResultsCache cache = properties.getResultsCache();
        final RedisClient client = RedisClient.create(cacheUri(cache, entraIdCredentials(resultsCacheCredential, cache.getUsername())));
        client.setOptions(client.getOptions().mutate()
                .socketOptions(client.getOptions().getSocketOptions().mutate()
                        .connectTimeout(cache.getConnectTimeout())
                        .build())
                .build());
        return client;
    }

    /** Reads the share's results; closed on context close (it is {@link AutoCloseable}). */
    @Bean
    public ResultsCacheClient resultsCacheClient(final RedisClient resultsCacheRedisClient) {
        return new ResultsCacheClient(resultsCacheRedisClient);
    }

    /**
     * The address the cache is reached on, TLS and credentials included. Separate from the client so it can be asserted:
     * where TLS is used the certificate is verified.
     */
    /* default */ static RedisURI cacheUri(final ResultsProperties.ResultsCache cache, final RedisCredentialsProvider credentials) {
        return RedisURI.builder()
                .withHost(cache.getHost())
                .withPort(cache.getPort())
                .withSsl(cache.isSsl())
                .withVerifyPeer(cache.isSsl())
                .withTimeout(cache.getCommandTimeout())
                .withAuthentication(credentials)
                .build();
    }

    /**
     * Username: the managed identity's object id. Password: a fresh Entra ID token for Redis, fetched on every new
     * connection (including Lettuce's reconnects), so an expired token is never reused.
     */
    /* default */ static RedisCredentialsProvider entraIdCredentials(final TokenCredential credential, final String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("cp.results-cache.username (the managed identity's object id) must be set when the"
                    + " Results cache is enabled");
        }
        // asynchronous: Lettuce asks for credentials on its event loop, which must not block on the token endpoint
        return () -> credential.getToken(new TokenRequestContext().addScopes(REDIS_SCOPE))
                .map(token -> RedisCredentials.just(username, token.getToken()));
    }
}
