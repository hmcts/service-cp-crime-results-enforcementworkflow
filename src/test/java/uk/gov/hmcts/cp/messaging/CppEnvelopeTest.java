package uk.gov.hmcts.cp.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 003 (research R12, constitution 1.2.0): the CPP envelope around the GOB data event. */
class CppEnvelopeTest {

    private static final UUID ID = UUID.fromString("6f1c2a4e-7b0d-4c1e-9a55-0d6a1e2b3c4d");

    @Test
    void created_at_should_be_truncated_to_milliseconds() {
        final ObjectNode envelope = CppEnvelope.wrap("public.x", "user", payload(), ID, Instant.parse("2026-10-23T09:15:30.123456789Z"));

        assertThat(envelope.path("_metadata").path("createdAt").asString()).isEqualTo("2026-10-23T09:15:30.123Z");
    }

    @Test
    void created_at_with_microseconds_should_have_exactly_three_fraction_digits() {
        final ObjectNode envelope = CppEnvelope.wrap("public.x", "user", payload(), ID, Instant.parse("2026-10-23T09:15:30.987654Z"));

        assertThat(envelope.path("_metadata").path("createdAt").asString()).matches("2026-10-23T09:15:30\\.987Z");
    }

    @Test
    void envelope_should_carry_the_metadata_then_the_payload_fields() {
        final ObjectNode envelope = CppEnvelope.wrap("public.x", "user-1", payload(), ID, Instant.parse("2026-10-23T09:15:30.5Z"));

        assertThat(envelope.path("_metadata").path("id").asString()).isEqualTo(ID.toString());
        assertThat(envelope.path("_metadata").path("name").asString()).isEqualTo("public.x");
        assertThat(envelope.path("_metadata").path("context").path("user").asString()).isEqualTo("user-1");
        assertThat(envelope.path("hearingId").asString()).isEqualTo("h");
    }

    private static ObjectNode payload() {
        final ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("hearingId", "h");
        return payload;
    }
}
