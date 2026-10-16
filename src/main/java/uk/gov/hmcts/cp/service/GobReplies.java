package uk.gov.hmcts.cp.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;

import java.util.Optional;

/** Reads what the GOB data event needs from a stored submission's request and reply (features 002 and 003). */
public final class GobReplies {

    private GobReplies() {
    }

    /** The GOB account number: the prosecutorDefendantId sent to GOB (feature 001 research R9); a blank one is none. */
    public static Optional<String> accountNumberOf(final String request) {
        return readTree(request).map(json -> json.path("defendantDetails").path("prosecutorDefendantId"))
                .filter(JsonNode::isString).map(JsonNode::asString).filter(account -> !account.isBlank());
    }

    /* default */ static Optional<JsonNode> readTree(final String json) {
        Optional<JsonNode> tree = Optional.empty();
        if (json != null) {
            try {
                tree = Optional.of(PayloadJson.MAPPER.readTree(json));
            } catch (JacksonException e) {
                tree = Optional.empty();
            }
        }
        return tree;
    }
}
