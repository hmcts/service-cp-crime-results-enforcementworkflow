package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** {@code cp.hearing-result.*}: rules for building the GOB hearing-result submission (data-model.md §4). */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.hearing-result")
public class HearingResultProperties {

    /** CP shortCode → GOB resultCode (research.md R5). Codes not listed pass through unchanged. */
    private Map<String, String> resultCodeRenames = new LinkedHashMap<>(Map.of("TFOUT", "TFOOUT", "WC", "DW", "WWDN", "WDN"));

    /** research.md R13. Default NONE; HEARING_DATE only via explicit local/test/simulator config. */
    private PaymentDueDateFallback paymentDueDateFallback = PaymentDueDateFallback.NONE;

    /** CP shortCode → GOB NowsDataItemName values (research.md R7). Rows come from the updated Confluence page. */
    private Map<String, List<String>> nowsDataItemsByShortCode = new LinkedHashMap<>();

    /** A SENDING row older than this is stale and is marked FAILED (INTERRUPTED), never resent (research.md R19). */
    private Duration staleSendingThreshold = Duration.ofMinutes(5);

    /** How often the stale-SENDING sweep runs (research.md R19). */
    private Duration staleSendingSweepInterval = Duration.ofMinutes(1);

    /** Fails startup when a configured NOWS data item isn't one of GOB's twelve NowsDataItemName values (FR-011). */
    @AssertTrue(message = "cp.hearing-result.nows-data-items-by-short-code must use GOB NowsDataItemName values")
    public boolean isNowsDataItemNamesValid() {
        return nowsDataItemsByShortCode.values().stream()
                .flatMap(List::stream)
                .allMatch(HearingResultProperties::isNowsDataItemName);
    }

    private static boolean isNowsDataItemName(final String name) {
        return Arrays.stream(NowsDataItemName.values()).anyMatch(n -> Objects.equals(n.getValue(), name));
    }
}
