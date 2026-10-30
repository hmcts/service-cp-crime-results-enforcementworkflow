package uk.gov.hmcts.cp.config;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import io.lettuce.core.RedisCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Feature 003 (research R8): the Results cache authenticates with an Entra ID token, never an access key. */
class RedisConfigTest {

    private final RedisConfig config = new RedisConfig();
    private final ResultsProperties properties = new ResultsProperties();

    @Test
    void client_id_should_select_that_managed_identity_otherwise_the_default_chain() {
        properties.getResultsCache().setManagedIdentityClientId("11111111-2222-3333-4444-555555555555");
        assertThat(config.resultsCacheCredential(properties)).isInstanceOf(ManagedIdentityCredential.class);

        properties.getResultsCache().setManagedIdentityClientId("");
        assertThat(config.resultsCacheCredential(properties)).isInstanceOf(DefaultAzureCredential.class);
    }

    @Test
    void connections_should_authenticate_with_the_identity_and_a_fresh_redis_token() {
        properties.getResultsCache().setUsername("object-id-of-the-identity");
        final TokenCredential credential = mock(TokenCredential.class);
        when(credential.getToken(any())).thenReturn(reactor.core.publisher.Mono.just(new AccessToken("entra-token", OffsetDateTime.now().plusHours(1))));
        final LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();

        config.entraIdCredentials(credential, properties).customize(builder);

        final RedisCredentials credentials = builder.build().getRedisCredentialsProviderFactory().orElseThrow()
                .createCredentialsProvider(new RedisStandaloneConfiguration()).resolveCredentials().block();
        assertThat(credentials.getUsername()).isEqualTo("object-id-of-the-identity");
        assertThat(new String(credentials.getPassword())).isEqualTo("entra-token");
    }

    @Test
    void enabled_cache_without_a_username_should_not_start() {
        properties.getResultsCache().setUsername(" ");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.entraIdCredentials(mock(TokenCredential.class), properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cp.results-cache.username");
    }
}
