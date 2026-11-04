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
 * {@code id} per message, the event {@code name} and {@code createdAt} (UTC), followed by the payload's fields at the top
 * level, as CPP's {@code DefaultJsonObjectEnvelopeConverter} reads it.
 *
 * <p>No {@code context.user}: this service has no system user of its own, and hearing-nows treats an event without a
 * user as its own context system user's ({@code EnforcementNowsDataEventProcessor}), which is the user allowed to issue
 * hearing-nows' own commands.
 */
public final class CppEnvelope {

    public static final String CPPNAME = "CPPNAME";

    private CppEnvelope() {
    }

    public static ObjectNode wrap(final String name, final ObjectNode payload, final UUID id, final Instant createdAt) {
        final ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        final ObjectNode metadata = envelope.putObject("_metadata");
        metadata.put("id", id.toString());
        metadata.put("name", name);
        // millisecond precision, like the CPP framework's own envelopes (Instant.toString can give 6 or 9 digits)
        metadata.put("createdAt", createdAt.truncatedTo(ChronoUnit.MILLIS).toString());
        for (final Map.Entry<String, JsonNode> field : payload.properties()) {
            envelope.set(field.getKey(), field.getValue());
        }
        return envelope;
    }
}
