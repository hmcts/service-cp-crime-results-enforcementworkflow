package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every catalogue default is written in the format the bundled v0.8.0 contract declares for its property: the schema
 * check ({@code OpenApiConformance}) validates types and patterns but not {@code x-date-format} or the "Notes:" formats,
 * so a date property once defaulted to "Monthly" unnoticed. hearing-nows prints these strings on the notice as they are.
 */
class FieldPathDefaultFormatTest {

    private static final String CONTRACT = "openapi/libra-gateway-hearing-events-v0.8.0.yml";
    private static final Pattern DATE = Pattern.compile("\\d{2} [A-Z][a-z]{2} \\d{4}");
    private static final Pattern CURRENCY = Pattern.compile("£\\d+\\.\\d{2}");
    private static final Set<String> PAYMENT_PERIODS = Set.of("Weekly", "Fortnightly", "Monthly");

    private final Catalogue catalogue = new CatalogueLoader().load();
    private final JsonNode schemas = contract().path("components").path("schemas");

    @Test
    void every_default_matches_the_format_of_its_contract_property() {
        final List<String> problems = new ArrayList<>();
        catalogue.fieldPaths().values().stream().filter(fieldPath -> !fieldPath.unmapped()).forEach(fieldPath -> {
            final JsonNode property = property(fieldPath.path());
            final String value = String.valueOf(fieldPath.defaultValue());
            final String notes = property.path("description").asText();
            if ("DD Mon YYYY".equals(property.path("x-date-format").asText()) && !DATE.matcher(value).matches()) {
                problems.add(fieldPath.label() + " is a DD Mon YYYY date: " + value);
            }
            if (property.has("pattern") && !Pattern.compile(property.path("pattern").asText()).matcher(value).find()) {
                problems.add(fieldPath.label() + " must match " + property.path("pattern").asText() + ": " + value);
            }
            if (notes.contains("Notes: Currency") && !CURRENCY.matcher(value).matches()) {
                problems.add(fieldPath.label() + " is currency text (£0.00): " + value);
            }
            if (notes.contains("ENUM(Weekly, Fortnightly, Monthly)") && !PAYMENT_PERIODS.contains(value)) {
                problems.add(fieldPath.label() + " is one of " + PAYMENT_PERIODS + ": " + value);
            }
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void the_terms_dates_and_amounts_are_checked() {
        assertThat(property("terms.english_due").path("x-date-format").asText()).isEqualTo("DD Mon YYYY");
        assertThat(property("terms.english_instalment").path("description").asText()).contains("Notes: Currency");
        assertThat(property("terms.english_lumpsum").path("description").asText()).contains("Notes: Currency");
    }

    /** The contract property a catalogue path writes, following $refs and array items. */
    private JsonNode property(final String path) {
        JsonNode node = schemas.path("NowsDataItems");
        for (final String segment : path.split("\\.")) {
            node = resolve(node);
            if ("array".equals(node.path("type").asText())) {
                node = resolve(node.path("items"));
            }
            node = node.path("properties").path(segment.replaceAll("\\[\\d+]", ""));
        }
        node = resolve(node);
        return "array".equals(node.path("type").asText()) ? resolve(node.path("items")) : node;
    }

    private JsonNode resolve(final JsonNode node) {
        return node.has("$ref") ? schemas.path(node.path("$ref").asText().substring("#/components/schemas/".length())) : node;
    }

    private static JsonNode contract() {
        try (InputStream in = FieldPathDefaultFormatTest.class.getClassLoader().getResourceAsStream(CONTRACT)) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
