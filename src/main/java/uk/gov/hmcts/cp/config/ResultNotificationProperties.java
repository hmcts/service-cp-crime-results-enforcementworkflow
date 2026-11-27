package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code cp.result-notification.*} (feature 005, spec.md Terms, plan.md Config): the Trigger ShortCode List (keyed by
 * {@link Area}, FR-001/FR-011), the configured Confiscation Division Code set (FR-003), the Docmosis template id per
 * {@link NotificationType}, and this feature's own retry/backoff schedule - independent of {@code cp.notification.*}
 * (research.md R4, FR-010).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.result-notification")
public class ResultNotificationProperties {

    /**
     * The 16 Enforcement codes and the (incomplete - spec.md Terms: "per the NOWS matrix") Confiscation-scoped codes
     * named so far. Configurable: an environment can extend either list without a code change.
     */
    @NotEmpty
    private Map<Area, List<String>> triggerShortCodes = new EnumMap<>(Map.of(
            Area.ENFORCEMENT, List.of("ACF", "AEC", "AEO", "AEOC", "CLAMPO", "CW", "CWN", "FIDIC", "FIDICT", "FIDIP",
                    "FTTP", "MPSO", "REGF", "S136", "TFOOUT", "WDN"),
            Area.CONFISCATION, List.of("BPOC")));

    /** Division Codes (as parsed integers) treated as Confiscation; every other Division Code is Enforcement (FR-003). */
    @NotEmpty
    private Set<Integer> confiscationDivisionCodes = Set.of(45, 66, 97, 109, 110, 116);

    /** The Docmosis {@code templateIdentifier} per notification type (contracts/system-doc-generator-byo-fs.md). */
    @NotEmpty
    private Map<NotificationType, String> docmosisTemplateIds = new EnumMap<>(Map.of(
            NotificationType.FIRST_SHARE, "NCESNotificationForNewResultTemplate",
            NotificationType.AMEND_RESHARE, "NCESNotificationForAmendAndReshareTemplate"));

    /** This feature's own retry back-off (research.md R4 - independent of {@code cp.notification.retry-durations}). */
    @NotEmpty
    private List<Duration> retryDurations = new ArrayList<>(List.of(Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofSeconds(300)));

    public boolean isTriggerShortCode(final Area area, final String shortCode) {
        return shortCode != null && triggerShortCodes.getOrDefault(area, List.of()).contains(shortCode);
    }
}
