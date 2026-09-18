package uk.gov.hmcts.cp.gobsimulator.guard;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Refuses to let the GOB simulator start anywhere it could be mistaken for the real Libra Gateway.
 *
 * <p>Listens for {@link ApplicationEnvironmentPreparedEvent}, which fires before any bean is
 * created — earlier than {@code @PostConstruct} — so a misdeployed pod crash-loops visibly rather
 * than serving fabricated court data.
 */
public final class LiveEnvironmentGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /* default */ static final String REQUIRED_PROFILE = "gob-simulator";
    /* default */ static final Set<String> FORBIDDEN_PROFILES =
            Set.of("prod", "production", "live", "perf", "preprod");

    @Override
    public void onApplicationEvent(final ApplicationEnvironmentPreparedEvent event) {
        check(Arrays.stream(event.getEnvironment().getActiveProfiles())
                .collect(Collectors.toUnmodifiableSet()));
    }

    /* default */ static void check(final Set<String> activeProfiles) {
        final Set<String> normalised = activeProfiles.stream()
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        final Set<String> forbidden = new TreeSet<>(normalised);
        forbidden.retainAll(FORBIDDEN_PROFILES);
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException(
                    "GOB simulator must never run in a live environment. Forbidden profile(s) active: "
                            + forbidden);
        }

        if (!normalised.contains(REQUIRED_PROFILE)) {
            throw new IllegalStateException(
                    "GOB simulator requires the '" + REQUIRED_PROFILE
                            + "' profile to be active. Active profiles: " + new TreeSet<>(normalised));
        }
    }
}
