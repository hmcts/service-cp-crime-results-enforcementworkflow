package uk.gov.hmcts.cp.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * The Common Platform event envelope (feature 003, research R12, constitution 1.2.0): {@code _metadata} with a new
 * {@code id} per message, the event {@code name}, {@code createdAt} (UTC) and {@code context.user}, followed by the
 * payload's fields at the top level, as CPP's {@code DefaultJsonObjectEnvelopeConverter} reads it.
 */
public final class CppEnvelope {

    public static final String CPPNAME = "CPPNAME";

    private CppEnvelope() {
    }

    public static ObjectNode wrap(final String name, final String user, final ObjectNode payload, final UUID id, final Instant createdAt) {
        final ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        final ObjectNode metadata = envelope.putObject("_metadata");
        metadata.put("id", id.toString());
        metadata.put("name", name);
        // millisecond precision, like the CPP framework's own envelopes (Instant.toString can give 6 or 9 digits)
        metadata.put("createdAt", createdAt.truncatedTo(ChronoUnit.MILLIS).toString());
        metadata.putObject("context").put("user", user);
        for (final Map.Entry<String, JsonNode> field : payload.properties()) {
            envelope.set(field.getKey(), field.getValue());
        }
        return envelope;
    }
}
