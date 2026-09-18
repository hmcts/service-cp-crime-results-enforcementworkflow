package uk.gov.hmcts.cp.gobsimulator.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.stereotype.Component;

/**
 * Loads the three catalogue resources and validates them against each other, and against the
 * bundled OpenAPI contract. Any inconsistency is a startup failure — the catalogue must never
 * silently return a wrong or empty answer.
 */
@Component
public class CatalogueLoader {

    private static final String DEFAULT_CATALOGUE_BASE = "gob-simulator/catalogue/";
    private static final String OPENAPI_SPEC_PATH = "openapi/libra-gateway-hearing-events-v0.4.0.yml";
    private static final String UNCHECKED = "unchecked";

    private final String catalogueBase;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public CatalogueLoader() {
        this(DEFAULT_CATALOGUE_BASE);
    }

    /**
     * Test-only entry point: loads the three catalogue resources from an alternate classpath
     * base directory (e.g. a deliberately broken fixture), while still validating against the
     * one bundled production OpenAPI contract.
     */
    public CatalogueLoader(final String catalogueBase) {
        this.catalogueBase = catalogueBase;
    }

    public Catalogue load() {
        final Map<String, List<String>> rawEntityNames = read(
                "entity-names.yaml", new TypeReference<>() { });
        final Map<String, Map<String, Object>> rawPaths = read(
                "field-paths.yaml", new TypeReference<>() { });
        final Map<String, Map<String, Object>> rawCodes = read(
                "result-codes.yaml", new TypeReference<>() { });

        final Map<String, List<String>> entityNames = new LinkedHashMap<>();
        rawEntityNames.forEach((name, properties) -> entityNames.put(name, List.copyOf(properties)));

        final Map<String, FieldPath> fieldPaths = new LinkedHashMap<>();
        rawPaths.forEach((label, row) -> fieldPaths.put(label, toFieldPath(label, row)));

        final Map<String, ResultCodeEntry> resultCodes = new LinkedHashMap<>();
        rawCodes.forEach((code, row) -> resultCodes.put(code, toResultCode(code, row)));

        final Catalogue catalogue = new Catalogue(
                Map.copyOf(entityNames), Map.copyOf(fieldPaths), Map.copyOf(resultCodes));

        final Map<String, Object> openApiSpec = readOpenApiSpec();
        final Set<String> schemaProperties = schemaNowsDataItemProperties(openApiSpec);
        final Set<String> schemaResultCodes = schemaResultCodeEnum(openApiSpec);

        validate(catalogue, schemaProperties, schemaResultCodes);
        return catalogue;
    }

    /**
     * The bundled OpenAPI contract's {@code resultCode} enum values. Exposed so callers (e.g.
     * {@code CatalogueCoverageTest}) can assert the catalogue against the schema without
     * hand-copying a second snapshot of the enum — this is the same parsing path {@link #load()}
     * uses for startup validation.
     */
    public Set<String> schemaResultCodes() {
        return schemaResultCodeEnum(readOpenApiSpec());
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

    @SuppressWarnings(UNCHECKED)
    private ResultCodeEntry toResultCode(final String code, final Map<String, Object> row) {
        final List<String> fields = (List<String>) row.getOrDefault("fields", List.of());
        return new ResultCodeEntry(
                code,
                List.copyOf(fields),
                (String) row.get("alias"),
                !Boolean.FALSE.equals(row.get("postable")),
                (String) row.get("note"));
    }

    // UseProperClassLoader targets J2EE app-server deployments, where the thread context
    // classloader can differ from the defining classloader. This is a Spring Boot executable
    // jar: getClass().getClassLoader() is the classloader that loaded this class (and its
    // bundled resources), which is correct and more predictable here than the TCCL.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private <T> T read(final String name, final TypeReference<T> type) {
        final String path = catalogueBase + name;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Catalogue resource not found: " + path);
            }
            return yaml.readValue(in, type);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read catalogue resource: " + path, e);
        }
    }

    // See the rationale on read(...) above: getClass().getClassLoader() is correct for this
    // Spring Boot executable jar's classloading model.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private Map<String, Object> readOpenApiSpec() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(OPENAPI_SPEC_PATH)) {
            if (in == null) {
                throw new IllegalStateException("OpenAPI spec resource not found: " + OPENAPI_SPEC_PATH);
            }
            return yaml.readValue(in, new TypeReference<>() { });
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read OpenAPI spec: " + OPENAPI_SPEC_PATH, e);
        }
    }

    /** The property names declared under {@code components.schemas.NowsDataItems.properties}. */
    @SuppressWarnings(UNCHECKED)
    private Set<String> schemaNowsDataItemProperties(final Map<String, Object> spec) {
        final Map<String, Object> nowsDataItems = schema(spec, "NowsDataItems");
        final Map<String, Object> properties = (Map<String, Object>) nowsDataItems.get("properties");
        if (properties == null) {
            throw new IllegalStateException(
                    "OpenAPI spec: components.schemas.NowsDataItems has no properties");
        }
        return Set.copyOf(properties.keySet());
    }

    /** The enum values of {@code components.schemas.HearingResult.properties.resultCode}. */
    @SuppressWarnings(UNCHECKED)
    private Set<String> schemaResultCodeEnum(final Map<String, Object> spec) {
        final Map<String, Object> hearingResult = schema(spec, "HearingResult");
        final Map<String, Object> properties = (Map<String, Object>) hearingResult.get("properties");
        final Map<String, Object> resultCode =
                properties == null ? null : (Map<String, Object>) properties.get("resultCode");
        final List<String> enumValues = resultCode == null ? null : (List<String>) resultCode.get("enum");
        if (enumValues == null) {
            throw new IllegalStateException(
                    "OpenAPI spec: components.schemas.HearingResult.properties.resultCode has no enum");
        }
        return Set.copyOf(enumValues);
    }

    @SuppressWarnings(UNCHECKED)
    private Map<String, Object> schema(final Map<String, Object> spec, final String name) {
        final Map<String, Object> components = (Map<String, Object>) spec.get("components");
        final Map<String, Object> schemas = components == null ? null : (Map<String, Object>) components.get("schemas");
        final Map<String, Object> result = schemas == null ? null : (Map<String, Object>) schemas.get(name);
        if (result == null) {
            throw new IllegalStateException("OpenAPI spec has no components.schemas." + name);
        }
        return result;
    }

    /** Spec §6.5 — every failure mode here aborts startup. */
    private void validate(
            final Catalogue catalogue, final Set<String> schemaProperties, final Set<String> schemaResultCodes) {
        final List<String> errors = new ArrayList<>();

        catalogue.entityNames().forEach((name, properties) -> {
            if (properties.isEmpty()) {
                errors.add("Entity name '" + name + "' maps to an empty property list");
            }
            properties.stream()
                    .filter(property -> !schemaProperties.contains(property))
                    .forEach(property -> errors.add(
                            "Entity name '" + name + "' targets unknown NowsDataItems property '" + property + "'"));
        });

        catalogue.fieldPaths().forEach((label, fieldPath) -> {
            if (fieldPath.unmapped()) {
                if (fieldPath.reason() == null || fieldPath.reason().isBlank()) {
                    errors.add("Unmapped label '" + label + "' has no reason");
                }
            } else if (fieldPath.path() == null || fieldPath.path().isBlank()) {
                errors.add("Label '" + label + "' has neither a path nor unmapped: true");
            } else if (!schemaProperties.contains(fieldPath.rootProperty())) {
                errors.add("Label '" + label + "' path '" + fieldPath.path() + "' has root property '"
                        + fieldPath.rootProperty() + "' which is not a known NowsDataItems property");
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

        schemaResultCodes.stream()
                .filter(code -> !catalogue.allCodes().contains(code))
                .forEach(code -> errors.add("Schema resultCode enum value '" + code
                        + "' is not known to the catalogue (must be mapped, aliased, or an explicit fields: [] "
                        + "gap row in result-codes.yaml)"));

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid GOB simulator catalogue:\n  " + String.join("\n  ", errors));
        }
    }
}
