package uk.gov.hmcts.cp.enforcementworkflowsimulator.idempotency;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedRequest;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedResponse;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.NowsDataItems;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding I2: {@code HearingResultControllerIT} exercises the cache only through the controller,
 * where a second-truncated timestamp and deterministic assembly make several of its assertions
 * trivially true whether or not anything is actually cached. These tests exercise {@link
 * IdempotencyCache} directly, in particular the request-binding rule ({@code
 * IdempotencyCache} javadoc) and eviction, neither of which the controller-level tests can
 * distinguish from "no cache at all".
 */
class IdempotencyCacheTest {

    private static final HearingResultedResponse RESPONSE_A =
            response("E011122334", "2026-05-03T10:00:00Z");
    private static final HearingResultedResponse RESPONSE_B =
            response("E098765432", "2026-05-03T10:00:05Z");

    @Test
    void a_hit_requires_both_the_key_and_the_stored_request_to_match() {
        final IdempotencyCache cache = new IdempotencyCache();
        final HearingResultedRequest request = request("E011122334");

        cache.put("key-1", request, RESPONSE_A);

        assertThat(cache.get("key-1", request)).contains(RESPONSE_A);
    }

    @Test
    void the_same_key_with_a_different_request_is_a_miss_not_a_replay() {
        final IdempotencyCache cache = new IdempotencyCache();
        final HearingResultedRequest first = request("E011122334");
        final HearingResultedRequest second = request("E098765432");

        cache.put("shared-key", first, RESPONSE_A);

        assertThat(cache.get("shared-key", second)).isEmpty();
    }

    @Test
    void a_fresh_response_for_a_same_key_different_request_replaces_the_stored_entry() {
        final IdempotencyCache cache = new IdempotencyCache();
        final HearingResultedRequest first = request("E011122334");
        final HearingResultedRequest second = request("E098765432");

        cache.put("shared-key", first, RESPONSE_A);
        // Simulate the controller's behaviour: a miss for the second request is followed by a put
        // of the fresh response under the same key.
        assertThat(cache.get("shared-key", second)).isEmpty();
        cache.put("shared-key", second, RESPONSE_B);

        assertThat(cache.get("shared-key", second)).contains(RESPONSE_B);
        // The first request's response is no longer reachable under the shared key.
        assertThat(cache.get("shared-key", first)).isEmpty();
    }

    @Test
    void a_null_key_never_caches_anything() {
        final IdempotencyCache cache = new IdempotencyCache();
        final HearingResultedRequest request = request("E011122334");

        cache.put(null, request, RESPONSE_A);

        assertThat(cache.get(null, request)).isEmpty();
    }

    @Test
    void evicts_the_oldest_entry_once_over_capacity() {
        final IdempotencyCache cache = new IdempotencyCache();
        final int capacity = 1000;

        for (int i = 0; i < capacity; i++) {
            cache.put("key-" + i, request("E01000000" + (i % 10)), RESPONSE_A);
        }
        // At exactly `capacity` entries, nothing has been evicted yet.
        assertThat(cache.get("key-0", request("E01000000" + (0 % 10)))).contains(RESPONSE_A);

        // One more entry pushes the cache over capacity and evicts the oldest (key-0).
        cache.put("key-" + capacity, request("E011122334"), RESPONSE_B);

        assertThat(cache.get("key-0", request("E01000000" + (0 % 10)))).isEmpty();
        assertThat(cache.get("key-" + capacity, request("E011122334"))).contains(RESPONSE_B);
    }

    private static HearingResultedRequest request(final String caseUrn) {
        return new HearingResultedRequest(
                caseUrn,
                "2026-05-03",
                "B01BH01",
                Map.of("prosecutorDefendantId", "1234567890", "address1", "1 Example Street"),
                null,
                null,
                Map.of("paymentDueDate", "2026-05-31", "paymentCardRequested", "N", "parentToPay", "N"),
                Map.of("prisonSentenceIndicator", "N"),
                List.of(new HearingResultedRequest.HearingResult("SC", null, null)),
                new HearingResultedRequest.NowsDataRequest(
                        List.of(new HearingResultedRequest.NowsDataItemRequest("Account Balance"))));
    }

    private static HearingResultedResponse response(final String caseUrn, final String timestamp) {
        return new HearingResultedResponse(caseUrn, timestamp, null,
                new NowsDataItems(null, null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
