package uk.gov.hmcts.cp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads one schema of an OpenAPI document on the test classpath. The whole document is the schema
 * root, so {@code #/components/schemas/...} refs resolve in place. Used by {@link LibraContract} and
 * {@link GatewayContract}.
 */
final class OpenApiSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenApiSchemas() {
    }

    static JsonSchema load(final String resource, final String schemaName) {
        try (InputStream in = OpenApiSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the test classpath");
            }
            final ObjectNode document = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(in);
            document.put("$ref", "#/components/schemas/" + schemaName);
            final JsonNode root = document;
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The schema violations for a JSON document (empty = valid). */
    static Set<String> violations(final JsonSchema schema, final String json) {
        try {
            final Set<ValidationMessage> messages = schema.validate(JSON.readTree(json));
            return messages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
