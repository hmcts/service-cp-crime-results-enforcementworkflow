package uk.gov.hmcts.cp.gobsimulator.guard;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveEnvironmentGuardTest {

    @Test
    void accepts_the_simulator_profile_on_its_own() {
        assertThatCode(() -> LiveEnvironmentGuard.check(Set.of("gob-simulator")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_a_live_profile_even_alongside_the_simulator_profile() {
        assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("gob-simulator", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must never run in a live environment")
                .hasMessageContaining("prod");
    }

    @Test
    void rejects_every_forbidden_profile_name_case_insensitively() {
        for (final String profile : Set.of("prod", "PRODUCTION", "Live", "perf", "preprod")) {
            assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("gob-simulator", profile)))
                    .as("profile %s must be rejected", profile)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejects_startup_when_the_simulator_profile_is_absent() {
        assertThatThrownBy(() -> LiveEnvironmentGuard.check(Set.of("docker")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the 'gob-simulator' profile");
    }
}
