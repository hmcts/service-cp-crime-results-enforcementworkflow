package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads per-caseUrn stub data. Bundled seeds live on the classpath; an external directory set via
 * {@code ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR} takes precedence so an environment can carry its own data.
 */
@Slf4j
@Component
public class SeedStore {

    /**
     * Finding I8: this is the ONLY path-traversal guard in the codebase, so it is documented
     * exactly, not aspirationally — a real caseUrn (e.g. {@code E011122334}) happens to be "E"
     * followed by 9 digits, but the guard itself accepts any 1-36 character string of ASCII
     * letters, digits, and hyphens. It rejects anything containing {@code .}, {@code /}, or
     * {@code \} (so {@code ../../application} cannot escape {@link #seedDir} via {@code
     * Path.resolve}), and anything over 36 characters, but it does NOT enforce the "E" + 9
     * digits shape. See the simulator README's "Known gaps" section.
     */
    private static final Pattern CASE_URN = Pattern.compile("^[A-Za-z0-9-]{1,36}$");
    private static final String CLASSPATH_BASE = "enforcement-workflow-simulator/seeds/";

    /**
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} makes the seed file the single source of truth for the
     * scale of a monetary value. Without it Jackson parses {@code 220.00} into a {@code double},
     * and the {@code .00} is gone before anything downstream can see it — an amount the catalogue
     * has no row for (a second or third imposition, which is pinned to index [0] in
     * field-paths.yaml and so unreachable by {@link ValueResolver}) would then reach the response
     * as {@code 220.0} and break AC4's "amounts numeric with 2 decimal places". Integers are
     * unaffected: a seeded {@code 903} has no decimal point, so it stays an integer and still
     * satisfies the contract's {@code type: integer} properties.
     *
     * <p>{@code withExactBigDecimals(true)} is required alongside it and is not optional tidying:
     * the default {@link JsonNodeFactory} calls {@code stripTrailingZeros()} on every BigDecimal
     * it wraps, which turns a seeded {@code 220.00} straight back into {@code 220} and undoes the
     * line above.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final String seedDir;

    public SeedStore(@Value("${enforcement-workflow-simulator.seed-dir:}") final String seedDir) {
        this.seedDir = seedDir;
    }

    public Optional<JsonNode> seedFor(final String caseUrn) {
        final boolean validCaseUrn = caseUrn != null && CASE_URN.matcher(caseUrn).matches();
        return validCaseUrn ? externalSeed(caseUrn).or(() -> classpathSeed(caseUrn)) : Optional.empty();
    }

    private Optional<JsonNode> externalSeed(final String caseUrn) {
        Optional<JsonNode> seed = Optional.empty();
        if (seedDir != null && !seedDir.isBlank()) {
            final Path file = Path.of(seedDir).resolve(caseUrn + ".json");
            if (Files.isRegularFile(file)) {
                try {
                    seed = Optional.of(mapper.readTree(file.toFile()));
                } catch (final IOException e) {
                    log.warn("Ignoring unreadable seed file: caseUrn={}, file={}", caseUrn, file, e);
                }
            }
        }
        return seed;
    }

    // See CatalogueLoader's rationale: getClass().getClassLoader() is correct for this Spring
    // Boot executable jar's classloading model, unlike the J2EE app-server case UseProperClassLoader targets.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private Optional<JsonNode> classpathSeed(final String caseUrn) {
        Optional<JsonNode> seed = Optional.empty();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(CLASSPATH_BASE + caseUrn + ".json")) {
            if (in != null) {
                seed = Optional.of(mapper.readTree(in));
            }
        } catch (final IOException e) {
            log.warn("Ignoring unreadable bundled seed: caseUrn={}", caseUrn, e);
        }
        return seed;
    }
}
