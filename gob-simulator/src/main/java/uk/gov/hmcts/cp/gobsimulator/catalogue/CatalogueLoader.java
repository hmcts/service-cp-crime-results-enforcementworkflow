package uk.gov.hmcts.cp.gobsimulator.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.stereotype.Component;

/**
 * Loads the three catalogue resources and validates them against each other. Any inconsistency is a
 * startup failure — the catalogue must never silently return a wrong or empty answer.
 */
@Component
public class CatalogueLoader {

    private static final String BASE = "gob-simulator/catalogue/";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Catalogue load() {
        final Map<String, List<String>> entityNames =
                read("entity-names.yaml", new TypeReference<LinkedHashMap<String, List<String>>>() { });
        final Map<String, Map<String, Object>> rawPaths =
                read("field-paths.yaml", new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { });
        final Map<String, Map<String, Object>> rawCodes =
                read("result-codes.yaml", new TypeReference<LinkedHashMap<String, Map<String, Object>>>() { });

        final Map<String, FieldPath> fieldPaths = new LinkedHashMap<>();
        rawPaths.forEach((label, row) -> fieldPaths.put(label, toFieldPath(label, row)));

        final Map<String, ResultCodeEntry> resultCodes = new LinkedHashMap<>();
        rawCodes.forEach((code, row) -> resultCodes.put(code, toResultCode(code, row)));

        final Catalogue catalogue = new Catalogue(
                Map.copyOf(entityNames), Map.copyOf(fieldPaths), Map.copyOf(resultCodes));
        validate(catalogue);
        return catalogue;
    }

    private FieldPath toFieldPath(final String label, final Map<String, Object> row) {
        final boolean unmapped = Boolean.TRUE.equals(row.get("unmapped"));
        return new FieldPath(
                label,
                (String) row.get("path"),
                (String) row.get("type"),
                row.get("default"),
                unmapped,
                (String) row.get("reason"));
    }

    @SuppressWarnings("unchecked")
    private ResultCodeEntry toResultCode(final String code, final Map<String, Object> row) {
        final List<String> fields = (List<String>) row.getOrDefault("fields", List.of());
        return new ResultCodeEntry(
                code,
                List.copyOf(fields),
                (String) row.get("alias"),
                !Boolean.FALSE.equals(row.get("postable")),
                (String) row.get("note"));
    }

    private <T> T read(final String name, final TypeReference<T> type) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("Catalogue resource not found: " + BASE + name);
            }
            return yaml.readValue(in, type);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read catalogue resource: " + BASE + name, e);
        }
    }

    /** Spec §6.5 — every failure mode here aborts startup. */
    private void validate(final Catalogue catalogue) {
        final List<String> errors = new ArrayList<>();

        catalogue.fieldPaths().forEach((label, fieldPath) -> {
            if (fieldPath.unmapped()) {
                if (fieldPath.reason() == null || fieldPath.reason().isBlank()) {
                    errors.add("Unmapped label '" + label + "' has no reason");
                }
            } else if (fieldPath.path() == null || fieldPath.path().isBlank()) {
                errors.add("Label '" + label + "' has neither a path nor unmapped: true");
            }
        });

        catalogue.resultCodes().forEach((code, entry) -> {
            if (entry.alias() != null && !catalogue.resultCodes().containsKey(entry.alias())) {
                errors.add("Result code '" + code + "' aliases unknown code '" + entry.alias() + "'");
            }
            entry.fields().stream()
                    .filter(label -> !catalogue.fieldPaths().containsKey(label))
                    .forEach(label -> errors.add(
                            "Result code '" + code + "' references unknown field label '" + label + "'"));
        });

        catalogue.allCodes().forEach(code -> {
            try {
                catalogue.fieldsFor(code);
            } catch (final IllegalStateException | IllegalArgumentException e) {
                errors.add("Result code '" + code + "' does not resolve: " + e.getMessage());
            }
        });

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid GOB simulator catalogue:\n  " + String.join("\n  ", errors));
        }
    }
}
