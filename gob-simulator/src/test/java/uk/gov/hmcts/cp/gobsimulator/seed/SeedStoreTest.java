package uk.gov.hmcts.cp.gobsimulator.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SeedStoreTest {

    private final SeedStore store = new SeedStore("");

    @Test
    void finds_a_seed_bundled_on_the_classpath() {
        assertThat(store.seedFor("E011122334")).isPresent();
    }

    @Test
    void returns_empty_for_an_unseeded_case_urn() {
        assertThat(store.seedFor("E999999999")).isEmpty();
    }

    @Test
    void does_not_confuse_case_urns_with_path_traversal() {
        assertThat(store.seedFor("../../application")).isEmpty();
    }

    // Finding I7: SeedStore("") — used by every other test in this class and, until now, by
    // every test in the whole suite — never exercises externalSeed()'s Path.of(seedDir).resolve
    // (...)/Files.isRegularFile/readTree(File) at all, because seedDir.isBlank() short-circuits
    // before any of that runs. GOB_SIMULATOR_SEED_DIR is documented in the README and wired
    // through application.yaml, so a real environment WILL exercise this path. These tests use a
    // non-blank seedDir (a @TempDir) to actually reach it.
    @Test
    void prefers_an_external_seed_over_the_bundled_classpath_one(@TempDir final Path seedDir) throws IOException {
        // E011122334 has a bundled classpath seed too (see finds_a_seed_bundled_on_the_classpath
        // above) — the external one must win.
        Files.writeString(seedDir.resolve("E011122334.json"), "{\"accountNumber\": \"EXTERNAL9999\"}");

        final SeedStore externalStore = new SeedStore(seedDir.toString());

        assertThat(externalStore.seedFor("E011122334")).isPresent();
        assertThat(externalStore.seedFor("E011122334").orElseThrow().get("accountNumber").asText())
                .isEqualTo("EXTERNAL9999");
    }

    @Test
    void falls_through_to_the_classpath_seed_when_the_external_file_is_missing(@TempDir final Path seedDir) {
        // seedDir exists (so externalSeed() actually runs Path.resolve/Files.isRegularFile) but
        // holds no file for this caseUrn — it must fall through to the bundled classpath seed.
        final SeedStore externalStore = new SeedStore(seedDir.toString());

        assertThat(externalStore.seedFor("E011122334")).isPresent();
    }

    @Test
    void returns_empty_when_neither_external_nor_classpath_has_the_case(@TempDir final Path seedDir) {
        final SeedStore externalStore = new SeedStore(seedDir.toString());

        assertThat(externalStore.seedFor("E999999999")).isEmpty();
    }

    // Finding I7: the existing traversal test uses SeedStore(""), so seedDir.isBlank() is true
    // and externalSeed() never even calls Path.resolve — the guard is only proven at the regex
    // level, not proven to survive an actual Path.resolve call with a non-blank seedDir. This
    // exercises the real resolve path: if the regex guard were removed, "../../application" would
    // resolve OUTSIDE seedDir back to a real file (e.g. application.yaml) sitting a few directories
    // up in the classpath/build output, and could leak it. With the guard in place, the caseUrn is
    // rejected before Path.resolve is even reached.
    @Test
    void the_traversal_guard_still_applies_with_a_non_blank_seed_dir(@TempDir final Path seedDir) throws IOException {
        Files.writeString(seedDir.resolve("E011122334.json"), "{\"accountNumber\": \"EXTERNAL9999\"}");

        final SeedStore externalStore = new SeedStore(seedDir.toString());

        assertThat(externalStore.seedFor("../../application")).isEmpty();
        assertThat(externalStore.seedFor("..%2F..%2Fapplication")).isEmpty();
    }
}
