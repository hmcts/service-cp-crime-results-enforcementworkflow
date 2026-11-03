package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** {@code cp.notification.*}: enforcement notifications from the results-processing Function App (feature 003). */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.notification")
public class NotificationProperties {

    /**
     * System users (CJSCPPUID) allowed to notify (spec FR-003, research R2). Empty means nobody can: the endpoint is
     * closed until the environment configures its Function App's system user.
     */
    private List<String> allowedSystemUsers = new ArrayList<>();

    /** Back-off between the attempts of a stage (research R10): 3 retries after the first attempt. */
    @NotEmpty
    private List<Duration> retryDurations = new ArrayList<>(List.of(Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofSeconds(300)));

    /** Automatic replays of a stored failure (research R10, spec FR-015); after the last one it stays stored. */
    @NotEmpty
    private List<Duration> replaySchedule = new ArrayList<>(List.of(Duration.ofMinutes(15), Duration.ofHours(1),
            Duration.ofHours(4), Duration.ofHours(12), Duration.ofHours(24)));

    @AssertTrue(message = "cp.notification.retry-durations and replay-schedule must be positive durations")
    public boolean isDurationsPositive() {
        return retryDurations.stream().allMatch(d -> !d.isNegative() && !d.isZero())
                && replaySchedule.stream().allMatch(d -> !d.isNegative() && !d.isZero());
    }

    public boolean isAllowed(final String systemUser) {
        return systemUser != null && allowedSystemUsers.stream().anyMatch(systemUser::equalsIgnoreCase);
    }
}
