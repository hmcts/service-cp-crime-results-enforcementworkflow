package uk.gov.hmcts.cp.integration;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.service.HearingResultsSource;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 003 (FR-006, research R7, R8): with the Results cache enabled, stage 1 reads the share's results from Redis
 * by hearing and day, authenticating with the managed identity's Entra ID token (here: a Redis ACL user whose password
 * is the token a stubbed credential returns; the default user is off, so no other login works). A miss falls back to
 * the Results query.
 */
@Import(ResultsCacheIntegrationTest.EntraIdStub.class)
class ResultsCacheIntegrationTest extends WorkflowStubsIntegrationTestBase {

    private static final String IDENTITY = "object-id-of-the-identity";
    private static final String TOKEN = "entra-id-token";
    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");

    @SuppressWarnings("resource") // stopped in @AfterAll
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--user", "default", "off", "--user", IDENTITY, "on", ">" + TOKEN, "~*", "+@all");

    static {
        REDIS.start();
    }

    @Autowired
    private HearingResultsSource source;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void cache(final DynamicPropertyRegistry registry) {
        registry.add("cp.results-cache.enabled", () -> "true");
        registry.add("cp.results-cache.username", () -> IDENTITY);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.ssl.enabled", () -> "false");
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Test
    void cached_share_should_be_read_with_the_entra_id_token_and_not_query_results() {
        // the cached payload's own day differs, to show the day is the notification's (the key's)
        redis.opsForValue().set("INT_" + HEARING_ID + "_" + DAY + "_result_", Fixtures.json("events/hearing-resulted-enforcement.json")
                .replace("\"hearingDay\": \"2026-05-03\"", "\"hearingDay\": \"2026-04-30\""));

        final HearingResultedEvent event = source.sharedResults(HEARING_ID, DAY, false).orElseThrow();

        assertThat(event.hearing().id()).isEqualTo(HEARING_ID);
        assertThat(event.hearingDay()).isEqualTo(DAY);
        STUBS.verify(0, getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID)));
    }

    @Test
    void cache_miss_should_fall_back_to_the_results_query() {
        redis.delete("INT_" + HEARING_ID + "_" + DAY + "_result_");
        stubResults(HEARING_ID, Fixtures.json("events/hearing-resulted-enforcement.json"));

        assertThat(source.sharedResults(HEARING_ID, DAY, false)).isPresent();
        STUBS.verify(1, getRequestedFor(urlEqualTo(RESULTS_PATH + HEARING_ID)));
    }

    /** Stands in for the managed identity: the token Redis accepts for the identity's ACL user. */
    @TestConfiguration
    static class EntraIdStub {
        @Bean
        @Primary
        TokenCredential resultsCacheCredentialStub() {
            return request -> Mono.just(new AccessToken(TOKEN, OffsetDateTime.now().plusHours(1)));
        }
    }
}
