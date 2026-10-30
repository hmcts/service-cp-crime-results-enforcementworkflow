package uk.gov.hmcts.cp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates GOB data event payloads (feature 003, research R12) against hearing-nows' event schema, a verbatim
 * test-resource copy in {@code contracts/hearingnows/} (constitution Principle II: the consumer owns the schema; this
 * service keeps a copy and validates every payload it builds against it). The schema's
 * {@code http://moj.gov.uk/cpp/hearingnows/...} references resolve to the local copies. Keep the copies byte-identical
 * with cpp-context-hearing-nows' {@code hearingnows-event-processor/src/yaml/json/schema/} files.
 */
public final class GobDataEventContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_ID = "http://moj.gov.uk/cpp/hearingnows/processor/public.enforcementworkflow.enforcement-nows-data-ready.json";
    private static final JsonSchema GOB_DATA_EVENT = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V4, builder -> builder.schemaMappers(mappers -> mappers
                    .mapPrefix("http://moj.gov.uk/cpp/hearingnows/nowdocument/", "classpath:contracts/hearingnows/")
                    .mapPrefix(SCHEMA_ID, "classpath:contracts/hearingnows/public.enforcementworkflow.enforcement-nows-data-ready.json")))
            .getSchema(SchemaLocation.of(SCHEMA_ID));

    private GobDataEventContract() {
    }

    /** Returns the schema violations of an event payload without its {@code _metadata} (empty = valid). */
    public static Set<String> violations(final String json) {
        try {
            final ObjectNode payload = (ObjectNode) JSON.readTree(json);
            payload.remove("_metadata");
            final Set<ValidationMessage> messages = GOB_DATA_EVENT.validate(payload);
            return messages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
