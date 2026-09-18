package uk.gov.hmcts.cp.gobsimulator.idempotency;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.gobsimulator.api.model.HearingResultedRequest;
import uk.gov.hmcts.cp.gobsimulator.api.model.HearingResultedResponse;

/**
 * Remembers responses by X-Idempotency-Key, bound to the request that produced them. AC7 only
 * defines replay for "the same key with the same body" — it says nothing about a caller reusing a
 * key across two different requests. Binding the cache to the request as well as the key is a
 * safe strengthening of that behaviour, not a contract change: a hit now requires both the key AND
 * the stored request to match (via {@code HearingResultedRequest}'s record {@code equals()}, which
 * compares every component structurally); a same-key request carrying a different body is treated
 * as a miss, so it is never satisfied from another case's cached response, and the fresh response
 * replaces the stored entry for that key.
 *
 * <p>A record's generated {@code equals()} was chosen over hashing the request: every component
 * of {@code HearingResultedRequest} (and its nested records/maps/lists) already has well-defined
 * structural equality, so a direct comparison is simpler and has no hash-collision or
 * JSON-key-ordering pitfalls to reason about.
 *
 * <p>Bounded and in-memory: the simulator is a test fixture, so surviving a restart is not
 * required.
 */
@Component
public class IdempotencyCache {

    private static final int MAX_ENTRIES = 1000;

    private final Map<String, CacheEntry> entries = new LinkedHashMap<>();

    public synchronized Optional<HearingResultedResponse> get(final String key,
                                                               final HearingResultedRequest request) {
        final CacheEntry entry = key == null ? null : entries.get(key);
        final boolean hit = entry != null && entry.request().equals(request);
        return hit ? Optional.of(entry.response()) : Optional.empty();
    }

    public synchronized void put(final String key, final HearingResultedRequest request,
                                  final HearingResultedResponse response) {
        if (key != null) {
            entries.put(key, new CacheEntry(request, response));
            evictOldestIfOverCapacity();
        }
    }

    private void evictOldestIfOverCapacity() {
        final Iterator<String> insertionOrder = entries.keySet().iterator();
        if (entries.size() > MAX_ENTRIES && insertionOrder.hasNext()) {
            insertionOrder.next();
            insertionOrder.remove();
        }
    }

    private record CacheEntry(HearingResultedRequest request, HearingResultedResponse response) {
    }
}
