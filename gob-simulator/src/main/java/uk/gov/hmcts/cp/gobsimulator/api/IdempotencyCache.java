package uk.gov.hmcts.cp.gobsimulator.api;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.gobsimulator.api.model.HearingResultedResponse;

/**
 * Remembers responses by X-Idempotency-Key. Bounded and in-memory: the simulator is a test
 * fixture, so surviving a restart is not required.
 */
@Component
public class IdempotencyCache {

    private static final int MAX_ENTRIES = 1000;

    private final Map<String, HearingResultedResponse> entries = new LinkedHashMap<>();

    public synchronized Optional<HearingResultedResponse> get(final String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(final String key, final HearingResultedResponse response) {
        if (key != null) {
            entries.put(key, response);
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
}
