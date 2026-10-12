package uk.gov.hmcts.cp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Validates hand-over payloads against the hearing-nows command schema, a verbatim test-resource copy in
 * {@code contracts/hearingnows/} (constitution Principle II v1.1.1: the consumer keeps a copy of the
 * hearing-nows schema and validates every payload it builds against it). The schema's
 * {@code http://moj.gov.uk/cpp/hearingnows/...} references resolve to the local copies.
 */
public final class HearingNowsContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema ADD_ENFORCEMENT_NOWS_DATA = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V4, builder -> builder.schemaMappers(mappers -> mappers
                    .mapPrefix("http://moj.gov.uk/cpp/hearingnows/nowdocument/", "classpath:contracts/hearingnows/")
                    .mapPrefix("http://moj.gov.uk/cpp/hearingnows/command/add-enforcement-nows-data.json",
                            "classpath:contracts/hearingnows/hearingnows.add-enforcement-nows-data.json")))
            .getSchema(SchemaLocation.of("http://moj.gov.uk/cpp/hearingnows/command/add-enforcement-nows-data.json"));

    private HearingNowsContract() {
    }

    /** Returns the schema violations for an add-enforcement-nows-data JSON document (empty = valid). */
    public static Set<String> addEnforcementNowsDataViolations(final String json) {
        try {
            final Set<ValidationMessage> messages = ADD_ENFORCEMENT_NOWS_DATA.validate(JSON.readTree(json));
            return messages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
