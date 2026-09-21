package uk.gov.hmcts.cp.gobsimulator.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private static Clock fixedAt(final Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void accepts_a_token_it_issued() {
        final TokenStore store = new TokenStore(fixedAt(NOW));

        assertThat(store.isValid(store.issue())).isTrue();
    }

    @Test
    void rejects_a_token_it_never_issued() {
        final TokenStore store = new TokenStore(fixedAt(NOW));
        store.issue();

        assertThat(store.isValid("a-token-from-somewhere-else")).isFalse();
    }

    @Test
    void rejects_a_null_token() {
        assertThat(new TokenStore(fixedAt(NOW)).isValid(null)).isFalse();
    }

    @Test
    void issues_distinct_tokens() {
        final TokenStore store = new TokenStore(fixedAt(NOW));

        assertThat(store.issue()).isNotEqualTo(store.issue());
    }

    /**
     * A mutable clock rather than two stores, because the token has to be issued and then checked
     * against the same store at a later instant — which is the whole behaviour under test.
     */
    @Test
    void rejects_a_token_once_its_advertised_lifetime_has_elapsed() {
        final MutableClock clock = new MutableClock(NOW);
        final TokenStore store = new TokenStore(clock);
        final String token = store.issue();

        clock.advance(Duration.ofSeconds(TokenStore.EXPIRES_IN_SECONDS - 1));
        assertThat(store.isValid(token)).as("one second before expiry").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(store.isValid(token)).as("exactly at expiry").isFalse();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        private void advance(final Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            throw new UnsupportedOperationException("not needed by these tests");
        }
    }
}
