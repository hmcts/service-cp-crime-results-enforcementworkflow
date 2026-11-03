package uk.gov.hmcts.cp.config;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.util.StringUtils;

/**
 * Results cache connection with Microsoft Entra ID (feature 003, research R8): every new connection authenticates
 * with a token for this service's managed identity, so no access key exists in config, env vars or Helm values
 * (CLAUDE.md, Azure SDK via Managed Identity). Host, port and SSL come from {@code spring.data.redis.*}. Only when
 * {@code cp.results-cache.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "cp.results-cache.enabled", havingValue = "true")
public class RedisConfig {

    /* default */ static final String REDIS_SCOPE = "https://redis.azure.com/.default";

    @Bean
    public TokenCredential resultsCacheCredential(final ResultsProperties properties) {
        final String clientId = properties.getResultsCache().getManagedIdentityClientId();
        return StringUtils.hasText(clientId)
                ? new ManagedIdentityCredentialBuilder().clientId(clientId).build()
                : new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    public LettuceClientConfigurationBuilderCustomizer entraIdCredentials(final TokenCredential resultsCacheCredential,
                                                                          final ResultsProperties properties) {
        final String username = properties.getResultsCache().getUsername();
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("cp.results-cache.username (the managed identity's object id) must be set when the"
                    + " Results cache is enabled");
        }
        // asynchronous: Lettuce asks for credentials on its event loop, which must not block on the token endpoint
        final RedisCredentialsProvider provider = () -> resultsCacheCredential.getToken(new TokenRequestContext().addScopes(REDIS_SCOPE))
                .map(token -> RedisCredentials.just(username, token.getToken()));
        return builder -> builder.redisCredentialsProviderFactory(new RedisCredentialsProviderFactory() {
            @Override
            public RedisCredentialsProvider createCredentialsProvider(final RedisConfiguration redisConfiguration) {
                return provider;
            }
        });
    }
}
