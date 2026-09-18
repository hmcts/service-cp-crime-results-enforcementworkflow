package uk.gov.hmcts.cp.gobsimulator.seed;

import org.junit.jupiter.api.Test;

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
}
