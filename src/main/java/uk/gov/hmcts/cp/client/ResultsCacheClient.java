package uk.gov.hmcts.cp.client;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads a share's results from the Results cache (feature 003, contracts/results-data-sources.md §1): key
 * {@code INT_{hearingId}_{hearingDay}_result_}, value the share's public-event payload, written by the Results context.
 * Built by {@link uk.gov.hmcts.cp.config.ResultsCacheConfig} only when {@code cp.results-cache.enabled=true}.
 *
 * <p>Same shape as service-cp-crime-informant-register's {@code LettuceHearingPayloadCache}: one string {@code GET} over
 * a connection opened on the first read (a cache that is down must not stop the service starting) and kept open, with
 * Lettuce's own reconnection behind it. A cache that cannot answer is a cache with nothing in it: {@link RedisException}
 * (refused connection, TLS, authentication, timeout) is a miss, and the Results query is the fallback. Nothing wider is
 * caught, so a fault that isn't the cache's is not hidden behind the fallback. Values hold personal data: never logged.
 */
@Slf4j
public class ResultsCacheClient implements AutoCloseable {

    private final RedisClient client;

    /** Last seen reachability, so a cache outage is logged once when it starts and once when it ends. */
    private final AtomicBoolean reachable = new AtomicBoolean(true);

    private StatefulRedisConnection<String, String> connection;

    public ResultsCacheClient(final RedisClient client) {
        this.client = client;
    }

    public Optional<String> sharedResults(final UUID hearingId, final LocalDate hearingDay) {
        final String key = keyOf(hearingId, hearingDay);
        Optional<String> value;
        try {
            value = Optional.ofNullable(openConnection().sync().get(key)).filter(json -> !json.isBlank());
            if (reachable.compareAndSet(false, true)) {
                log.info("The Results cache is answering again");
            }
            // TEMP DIAGNOSTICS: key, hit or miss, and the cached value
            TempPayloadLog.info("Results cache GET {}: {}, value={}", key, value.isPresent() ? "hit" : "miss", value.orElse(null));
        } catch (RedisException e) {
            // by type only: the message can name the address and the identity the connection was attempted with
            if (reachable.compareAndSet(true, false)) {
                log.error("The Results cache has become unreachable; every read now falls back to the Results query ({})",
                        e.getClass().getSimpleName());
            }
            log.warn("Results cache read for hearing {} on {} failed ({}); using the Results query", hearingId, hearingDay,
                    e.getClass().getSimpleName());
            // TEMP DIAGNOSTICS: message, root cause and stack trace
            TempPayloadLog.error("Results cache GET {} failed: {} - {} (rootCause={})", key, e.getClass().getSimpleName(),
                    e.getMessage(), TempDiagnostics.rootCause(e).toString(), e);
            value = Optional.empty();
        }
        return value;
    }

    /** The open connection, opening one when there isn't a usable one (first read, or closed for good). */
    private synchronized StatefulRedisConnection<String, String> openConnection() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
    }

    /* default */ static String keyOf(final UUID hearingId, final LocalDate hearingDay) {
        return "INT_" + hearingId + "_" + hearingDay + "_result_";
    }
}
