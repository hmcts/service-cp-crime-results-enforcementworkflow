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
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates outbound payloads against the Libra Gateway contract, a test-resource copy at {@value #CONTRACT} (constitution
 * Principle VII: payloads built for external contracts are validated against the contract schema).
 * The whole OpenAPI document is the schema root, so {@code #/components/schemas/...} refs resolve in place.
 */
public final class LibraContract {

    /** Test resource copy of the Libra contract (with the documented local amendment: nowsDataRequest optional). */
    private static final String CONTRACT = "contracts/libra-gateway-hearing-events-openapi-v0.4.0.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema HEARING_RESULTED_REQUEST = schemaFor("HearingResultedRequest");

    private LibraContract() {
    }

    /** Returns the schema violations for a HearingResultedRequest JSON document (empty = valid). */
    public static Set<String> hearingResultedRequestViolations(final String json) {
        try {
            final Set<ValidationMessage> messages = HEARING_RESULTED_REQUEST.validate(JSON.readTree(json));
            return messages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonSchema schemaFor(final String schemaName) {
        try {
            final InputStream in = LibraContract.class.getClassLoader().getResourceAsStream(CONTRACT);
            if (in == null) {
                throw new IllegalStateException(CONTRACT + " not found on the test classpath (src/test/resources)");
            }
            final ObjectNode document;
            try (in) {
                document = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(in);
            }
            document.put("$ref", "#/components/schemas/" + schemaName);
            final JsonNode root = document;
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
