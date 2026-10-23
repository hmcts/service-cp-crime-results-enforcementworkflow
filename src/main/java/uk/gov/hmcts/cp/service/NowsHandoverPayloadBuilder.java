package uk.gov.hmcts.cp.service;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the GOB data event payload for hearing-nows (feature 003, research R12). GOB's {@code nowsDataItems} are
 * embedded as the JSON node of GOB's raw reply, unchanged (feature 002 research R10): no field added, removed,
 * renamed or reformatted. The payload holds GOB data and the account number: never logged.
 */
@Component
public class NowsHandoverPayloadBuilder {

    /** Keeps GOB's numbers as written (e.g. {@code 125.50} stays {@code 125.50}): decimals as BigDecimal, written plain. */
    private static final JsonMapper VERBATIM = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /**
     * The GOB data event payload (feature 003, contracts/public.enforcementworkflow.enforcement-nows-data-ready.json),
     * without the CPP envelope: the notification's NOWs of the selected defendant, the account number and GOB's
     * {@code nowsDataItems} verbatim.
     */
    public ObjectNode build(final UUID hearingId, final List<NowRequest> nows, final String accountNumber, final JsonNode nowsDataItems,
                            final boolean gobDataReused, final Instant sharedTime) {
        final ObjectNode payload = VERBATIM.createObjectNode();
        payload.put("hearingId", hearingId.toString());
        final ArrayNode items = payload.putArray("nows");
        nows.forEach(now -> items.addObject()
                .put("requestId", now.requestId().toString())
                .put("masterDefendantId", now.masterDefendantId().toString()));
        payload.put("accountNumber", accountNumber);
        payload.put("gobDataReused", gobDataReused);
        if (sharedTime != null) {
            payload.put("sharedTime", sharedTime.toString());
        }
        payload.set("nowsDataItems", nowsDataItems);
        return payload;
    }

    /** Writes a payload keeping GOB's numbers as written. */
    public static String write(final JsonNode json) {
        return VERBATIM.writeValueAsString(json);
    }

    /** One enforcement NOW request of the notification. */
    public record NowRequest(UUID requestId, UUID masterDefendantId) {
    }

    /**
     * GOB's {@code nowsDataItems} from a stored or received reply: the node as GOB sent it, an empty object when GOB
     * returned none, or empty when the reply is not a JSON object (nothing to hand over).
     */
    public static Optional<JsonNode> nowsDataItemsOf(final String reply) {
        Optional<JsonNode> items = Optional.empty();
        if (reply != null && !reply.isBlank()) {
            try {
                final JsonNode json = VERBATIM.readTree(reply);
                if (json.isObject()) {
                    final JsonNode node = json.get("nowsDataItems");
                    items = Optional.of(node == null || node.isNull() ? VERBATIM.createObjectNode() : node);
                }
            } catch (JacksonException e) {
                items = Optional.empty();
            }
        }
        return items;
    }
}
