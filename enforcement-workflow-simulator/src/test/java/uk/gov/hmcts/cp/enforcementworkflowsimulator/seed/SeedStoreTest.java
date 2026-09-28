package uk.gov.hmcts.cp.enforcementworkflowsimulator.seed;

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

    // Finding (final wave, item 3): this test used to assert seedFor("../../application") is
    // empty with SeedStore("") — seedDir.isBlank() is true, so externalSeed() short-circuits
    // before Path.resolve ever runs, AND no application.json sits anywhere near the classpath
    // base or the build tree that classpathSeed() could resolve to either. It passed whether or
    // not the CASE_URN guard rejected the string — "the guard rejected it" and "the file wasn't
    // there" were indistinguishable, the exact failure mode this effort has been chasing.
    // Removed rather than "fixed" here: the_traversal_guard_still_applies_with_a_non_blank_seed_dir
    // below is the genuinely falsifiable version of this same assertion (a real, non-blank
    // seedDir and a real file placed where a resolved ".." would actually reach it), so this test
    // adds no coverage that one doesn't already provide, and constructing an actually-reachable
    // classpath escape for classpathSeed() (the only path this SeedStore("") instance can reach)
    // would just duplicate that test's intent against a resource loader instead of a filesystem.

    // Finding I7: SeedStore("") — used by every other test in this class and, until now, by
    // every test in the whole suite — never exercises externalSeed()'s Path.of(seedDir).resolve
    // (...)/Files.isRegularFile/readTree(File) at all, because seedDir.isBlank() short-circuits
    // before any of that runs. ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR is documented in the README and wired
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

    // Finding I7 / final wave item 3: the previous version of this test used SeedStore("") and
    // asserted seedFor("../../application") is empty — but with seedDir blank, externalSeed()
    // never calls Path.resolve at all (seedDir.isBlank() short-circuits first), and no
    // application.json sits anywhere near the temp dir or the build tree for classpathSeed() to
    // find either. The test passed whether or not the CASE_URN guard rejected the string —
    // "the guard rejected it" and "the file wasn't there" were indistinguishable, which is the
    // exact failure mode this whole effort has been chasing, reintroduced in the test written to
    // fix it.
    //
    // This version makes the guard's absence actually observable: a real file
    // (root/escaped.json) sits ONE level above a real, non-blank seedDir (root/seeds), so
    // "../escaped" is a genuinely reachable relative path from seedDir — Path.of(seedDir)
    // .resolve("../escaped.json") resolves to root/escaped.json, which exists and is readable.
    // With the CASE_URN regex guard in place, "../escaped" is rejected before externalSeed() ever
    // calls Path.resolve, so the result must be empty; without the guard, it would return the
    // escaped file's content. Confirmed by negative control: temporarily relaxing CASE_URN to
    // also accept '.' and '/' made this test fail (seedFor("../escaped") returned the escaped
    // file's content, not empty) — see final-wave-report.md for the exact output — before the
    // guard was restored.
    @Test
    void the_traversal_guard_still_applies_with_a_non_blank_seed_dir(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("escaped.json"), "{\"accountNumber\": \"ESCAPED9999\"}");
        final Path seedDir = root.resolve("seeds");
        Files.createDirectory(seedDir);

        final SeedStore externalStore = new SeedStore(seedDir.toString());

        assertThat(externalStore.seedFor("../escaped")).isEmpty();
    }
}
