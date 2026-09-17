package uk.gov.hmcts.cp.gobsimulator.guard;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import uk.gov.hmcts.cp.gobsimulator.GobSimulatorApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Exercises the real wiring between Spring and {@link LiveEnvironmentGuard} — the path
 * {@link GobSimulatorApplication#main(String[])} actually sets up — rather than only the
 * package-private predicate covered by {@link LiveEnvironmentGuardTest}.
 */
class LiveEnvironmentGuardStartupTest {

    @Test
    void application_startup_aborts_when_a_forbidden_profile_is_active() {
        final SpringApplication application = new SpringApplication(GobSimulatorApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners(new LiveEnvironmentGuard());

        final Throwable thrown =
                catchThrowable(() -> application.run("--spring.profiles.active=gob-simulator,prod"));

        assertThat(thrown).isNotNull();
        assertThat(rootCauseOf(thrown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must never run in a live environment")
                .hasMessageContaining("prod");
    }

    @Test
    void application_startup_succeeds_under_the_simulator_profile_alone() {
        final SpringApplication application = new SpringApplication(GobSimulatorApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners(new LiveEnvironmentGuard());

        final ConfigurableApplicationContext context =
                application.run("--spring.profiles.active=gob-simulator");
        try {
            assertThat(context.isActive()).isTrue();
        } finally {
            context.close();
        }
    }

    @Test
    void guard_reads_the_environments_active_profiles_not_its_default_profiles() {
        final StandardEnvironment environment = new StandardEnvironment();
        // Deliberately set only the *default* profile, and leave the *active* profiles empty.
        // If the guard's delegate read getDefaultProfiles() instead of getActiveProfiles(),
        // this would wrongly be accepted as "gob-simulator" being active.
        environment.setDefaultProfiles("gob-simulator");

        final ApplicationEnvironmentPreparedEvent event = new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(),
                new SpringApplication(GobSimulatorApplication.class),
                new String[0],
                environment);

        final Throwable thrown =
                catchThrowable(() -> new LiveEnvironmentGuard().onApplicationEvent(event));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the 'gob-simulator' profile");
    }

    private static Throwable rootCauseOf(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
