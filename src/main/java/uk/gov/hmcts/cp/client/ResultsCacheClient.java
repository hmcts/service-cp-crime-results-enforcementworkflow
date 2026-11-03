package uk.gov.hmcts.cp.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a share's results from the Results cache (feature 003, contracts/results-data-sources.md §1): key
 * {@code INT_{hearingId}_{hearingDay}_result_}, value the share's public-event payload, written by the Results context.
 * Only when {@code cp.results-cache.enabled=true} (Entra ID auth, research R8). A failure is a miss, not an error: the
 * Results query is the fallback. Values hold personal data: never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cp.results-cache.enabled", havingValue = "true")
public class ResultsCacheClient {

    private final StringRedisTemplate redisTemplate;

    public Optional<String> sharedResults(final UUID hearingId, final LocalDate hearingDay) {
        Optional<String> value;
        try {
            value = Optional.ofNullable(redisTemplate.opsForValue().get(keyOf(hearingId, hearingDay))).filter(json -> !json.isBlank());
            // TEMP DIAGNOSTICS: key, hit or miss, and the cached value
            TempPayloadLog.info("Results cache GET {}: {}, value={}", keyOf(hearingId, hearingDay), value.isPresent() ? "hit" : "miss",
                    value.orElse(null));
        } catch (DataAccessException e) {
            log.warn("Results cache read for hearing {} on {} failed ({}); using the Results query", hearingId, hearingDay,
                    e.getClass().getSimpleName());
            // TEMP DIAGNOSTICS: message, root cause and stack trace
            TempPayloadLog.error("Results cache GET {} failed: {} - {} (rootCause={})", keyOf(hearingId, hearingDay),
                    e.getClass().getSimpleName(), e.getMessage(), TempDiagnostics.rootCause(e).toString(), e);
            value = Optional.empty();
        }
        return value;
    }

    /* default */ static String keyOf(final UUID hearingId, final LocalDate hearingDay) {
        return "INT_" + hearingId + "_" + hearingDay + "_result_";
    }
}
