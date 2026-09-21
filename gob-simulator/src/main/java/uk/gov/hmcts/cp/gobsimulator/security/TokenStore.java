package uk.gov.hmcts.cp.gobsimulator.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The set of bearer tokens this simulator has issued, with their expiry.
 *
 * <p>The contract declares an opaque client-credentials token, so there is nothing inside a token
 * to verify — the only way to know a caller completed the {@code /auth/token} exchange is to
 * remember what was handed out. A presence-only check on the {@code Authorization} header would
 * accept {@code Bearer anything}, which lets an integration pass without ever acquiring a token:
 * precisely the defect this simulator exists to catch before Common Platform meets the real Libra
 * Gateway. See ADR-004.
 *
 * <p>The {@link Clock} is injected so expiry is exercised by moving the clock rather than by
 * sleeping for an hour.
 *
 * <p>In-memory and per-instance: a restart invalidates every outstanding token, and two replicas
 * would not share them. Both are acceptable for a single-instance, non-live stub and are recorded
 * as assumptions in ADR-004.
 */
@Component
public class TokenStore {

    /** Advertised to callers as {@code expires_in}, and the lifetime actually enforced here. */
    public static final int EXPIRES_IN_SECONDS = 3600;

    private static final Duration LIFETIME = Duration.ofSeconds(EXPIRES_IN_SECONDS);

    private final Map<String, Instant> expiryByToken = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenStore(final Clock clock) {
        this.clock = clock;
    }

    /** Mints an opaque token and records its expiry. */
    public String issue() {
        final String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        expiryByToken.put(token, clock.instant().plus(LIFETIME));
        return token;
    }

    /**
     * True only for a token this store issued and whose lifetime has not elapsed. Expired entries
     * are dropped as they are encountered, which is sweep enough for a simulator's traffic.
     */
    public boolean isValid(final String token) {
        // Guarded rather than passed straight to get(): ConcurrentHashMap throws on a null key.
        final Instant expiry = token == null ? null : expiryByToken.get(token);
        final boolean valid = expiry != null && clock.instant().isBefore(expiry);

        if (expiry != null && !valid) {
            expiryByToken.remove(token);
        }
        return valid;
    }
}
