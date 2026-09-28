package uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefendantDetailsOverlayTest {

    private final DefendantDetailsOverlay overlay = new DefendantDetailsOverlay();

    /**
     * The contract's NOWS dates use a three-letter month ({@code 15 Jan 2026}, {@code 31 May 2026}
     * — see field-paths.yaml's own defaults), and every seeded date in the fixtures follows it.
     *
     * <p>This covers all twelve months deliberately, because exactly one of them is a trap:
     * {@code DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK)} renders September as
     * <strong>Sept</strong>, not {@code Sep}, since that is what CLDR declares for en-GB. Every
     * other month is three letters in both conventions, so a fixture that happens to use any other
     * month passes while the formatter is wrong — which is exactly how this shipped. Asserting one
     * month, or trusting a locale, would not be falsifiable.
     */
    @Test
    void formats_every_month_to_the_contracts_three_letter_abbreviation() {
        final List<String> expected = List.of(
                "05 Jan 1975", "05 Feb 1975", "05 Mar 1975", "05 Apr 1975",
                "05 May 1975", "05 Jun 1975", "05 Jul 1975", "05 Aug 1975",
                "05 Sep 1975", "05 Oct 1975", "05 Nov 1975", "05 Dec 1975");

        final List<String> actual = IntStream.rangeClosed(1, 12)
                .mapToObj(month -> String.format("1975-%02d-05", month))
                .map(isoDate -> dobFor(Map.of("dateOfBirth", isoDate)))
                .toList();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void omits_the_date_of_birth_entirely_when_it_cannot_be_parsed() {
        assertThat(overlay.from(Map.of("dateOfBirth", "not-a-date"))).isEmpty();
    }

    @Test
    void writes_every_address_line_blanking_the_ones_the_caller_omitted() {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("address1", "18 Roundhay Road");
        details.put("address3", "Leeds");
        details.put("postcode", "LS7 3JB");

        assertThat(defendant(overlay.from(details)).get("defAddress")).isEqualTo(Map.of(
                "defAddressLine1", "18 Roundhay Road",
                "defAddressLine2", "",
                "defAddressLine3", "Leeds",
                "defPostcode", "LS7 3JB"));
    }

    // A field the caller omitted must produce NO overlay entry rather than an empty one, so the
    // seed still supplies it — homeTelNo is the live example across the fixtures.
    @Test
    void contributes_nothing_for_a_field_the_caller_omitted() {
        assertThat(defendant(overlay.from(Map.of("forename", "William", "surname", "Owens"))))
                .containsOnlyKeys("defName");
    }

    @Test
    void maps_the_prosecutor_defendant_id_to_the_account_number() {
        assertThat(overlay.from(Map.of("prosecutorDefendantId", "5588998811")))
                .containsEntry("accountNumber", "5588998811");
    }

    private String dobFor(final Map<String, Object> details) {
        return defendant(overlay.from(details)).get("DoB").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defendant(final Map<String, Object> overlayTree) {
        return (Map<String, Object>) overlayTree.get("defendant");
    }
}
