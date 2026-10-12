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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the {@code hearingnows.add-enforcement-nows-data} payload (feature 002, data-model §2). GOB's
 * {@code nowsDataItems} are embedded as the JSON node of GOB's raw reply, unchanged (research R10): no field added,
 * removed, renamed or reformatted. The payload holds GOB data and the account number: never logged.
 */
@Component
public class NowsHandoverPayloadBuilder {

    /** Keeps GOB's numbers as written (e.g. {@code 125.50} stays {@code 125.50}): decimals as BigDecimal, written plain. */
    private static final JsonMapper VERBATIM = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    public String build(final SubmissionKey key, final UUID masterDefendantId, final String accountNumber, final JsonNode nowsDataItems,
                        final boolean gobDataReused, final List<UUID> coveredResultDefinitionIds) {
        final ObjectNode payload = VERBATIM.createObjectNode();
        payload.put("correlationId", key.correlationId().toString());
        payload.put("hearingId", key.hearingId().toString());
        payload.put("hearingDay", key.hearingDay().toString());
        payload.put("masterDefendantId", masterDefendantId.toString());
        payload.put("defendantId", key.defendantId().toString());
        payload.put("prosecutionCaseId", key.caseId().toString());
        payload.put("caseUrn", key.caseUrn());
        payload.put("accountNumber", accountNumber);
        payload.put("sharedTime", key.sharedTime().toString());
        payload.put("gobDataReused", gobDataReused);
        final ArrayNode covered = payload.putArray("coveredResultDefinitionIds");
        coveredResultDefinitionIds.forEach(id -> covered.add(id.toString()));
        payload.set("nowsDataItems", nowsDataItems);
        return VERBATIM.writeValueAsString(payload);
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
