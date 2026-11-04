package uk.gov.hmcts.cp.config;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Feature 003 (research R8): what the Results cache connection is made with. Entra ID token, never an access key. */
class ResultsCacheConfigTest {

    private final ResultsCacheConfig config = new ResultsCacheConfig();
    private final ResultsProperties properties = new ResultsProperties();
    private final RedisCredentialsProvider credentials = mock(RedisCredentialsProvider.class);

    private ResultsProperties.ResultsCache cache(final boolean ssl) {
        final ResultsProperties.ResultsCache cache = properties.getResultsCache();
        cache.setHost("redis-ste-ccm-01.example");
        cache.setPort(10_000);
        cache.setSsl(ssl);
        cache.setCommandTimeout(Duration.ofSeconds(3));
        return cache;
    }

    @Test
    void client_id_should_select_that_managed_identity_otherwise_the_default_chain() {
        properties.getResultsCache().setManagedIdentityClientId("11111111-2222-3333-4444-555555555555");
        assertThat(config.resultsCacheCredential(properties)).isInstanceOf(ManagedIdentityCredential.class);

        properties.getResultsCache().setManagedIdentityClientId("");
        assertThat(config.resultsCacheCredential(properties)).isInstanceOf(DefaultAzureCredential.class);
    }

    @Test
    void connection_should_carry_the_configured_address_timeout_and_entra_id_credentials() {
        final RedisURI uri = ResultsCacheConfig.cacheUri(cache(true), credentials);

        assertThat(uri.getHost()).isEqualTo("redis-ste-ccm-01.example");
        assertThat(uri.getPort()).isEqualTo(10_000);
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(uri.getCredentialsProvider()).isSameAs(credentials);
    }

    @Test
    void tls_connection_should_verify_the_certificate() {
        final RedisURI uri = ResultsCacheConfig.cacheUri(cache(true), credentials);

        assertThat(uri.isSsl()).isTrue();
        assertThat(uri.isVerifyPeer()).isTrue();
    }

    @Test
    void plain_local_connection_should_not_ask_for_verification() {
        final RedisURI uri = ResultsCacheConfig.cacheUri(cache(false), credentials);

        assertThat(uri.isSsl()).isFalse();
        assertThat(uri.isVerifyPeer()).isFalse();
    }

    @Test
    void credentials_should_be_the_identity_and_a_fresh_redis_token() {
        final TokenCredential credential = mock(TokenCredential.class);
        when(credential.getToken(any())).thenReturn(Mono.just(new AccessToken("entra-token", OffsetDateTime.now().plusHours(1))));

        final RedisCredentials resolved = ResultsCacheConfig.entraIdCredentials(credential, "object-id-of-the-identity")
                .resolveCredentials().block();

        assertThat(resolved.getUsername()).isEqualTo("object-id-of-the-identity");
        assertThat(new String(resolved.getPassword())).isEqualTo("entra-token");
    }

    @Test
    void enabled_cache_without_a_username_should_not_start() {
        assertThatThrownBy(() -> ResultsCacheConfig.entraIdCredentials(mock(TokenCredential.class), " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cp.results-cache.username");
    }

    @Test
    void local_defaults_should_be_a_plain_local_server() {
        final ResultsProperties.ResultsCache defaults = new ResultsProperties().getResultsCache();

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getHost()).isEqualTo("localhost");
        assertThat(defaults.getPort()).isEqualTo(6379);
        assertThat(defaults.isSsl()).isFalse();
    }
}
