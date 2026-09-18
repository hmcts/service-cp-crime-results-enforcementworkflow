package uk.gov.hmcts.cp.gobsimulator.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads per-caseUrn stub data. Bundled seeds live on the classpath; an external directory set via
 * {@code GOB_SIMULATOR_SEED_DIR} takes precedence so an environment can carry its own data.
 */
@Slf4j
@Component
public class SeedStore {

    /** Case URNs are E followed by 9 digits; anything else cannot name a seed file. */
    private static final Pattern CASE_URN = Pattern.compile("^[A-Za-z0-9-]{1,36}$");
    private static final String CLASSPATH_BASE = "gob-simulator/seeds/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String seedDir;

    public SeedStore(@Value("${gob.simulator.seed-dir:}") final String seedDir) {
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
