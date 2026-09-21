package uk.gov.hmcts.cp.gobsimulator.assembly;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns the {@code defendantDetails} the caller posted into a NowsDataItems overlay.
 *
 * <p>Libra does not invent a defendant: the identity on the NOWS is the identity the court just
 * posted. Everything here is therefore a straight echo of the request, applied at the HIGHEST
 * precedence in {@link NowsDataItemsAssembler} — above the seed and above the catalogue default —
 * so a new case needs no seed edit to come back with the right person on it.
 *
 * <p>A field the caller omitted produces NO overlay entry, rather than an empty one, so the seed
 * still supplies it ({@code homeTelNo} is the live example: the contract declares
 * {@code homeTelephoneNumber} but a caller may omit it, and the seed then fills it).
 *
 * <p>Two deliberate limits, both forced by the contract rather than chosen here:
 * <ul>
 *   <li>{@code address4} and {@code address5} are dropped — {@code DefAddress} declares three
 *       lines plus a postcode and is {@code additionalProperties: false}, so there is nowhere to
 *       put them.</li>
 *   <li>{@code dateOfBirth} is reformatted from the request's ISO {@code yyyy-MM-dd} to the
 *       {@code dd MMM yyyy} shape the contract's NOWS dates use. An unparseable value is dropped
 *       with a warning rather than passed through: a malformed date reaching CP as a plausible
 *       looking {@code DoB} is worse than an absent one.</li>
 * </ul>
 */
@Slf4j
@Component
public class DefendantDetailsOverlay {

    /**
     * The three-letter month names the NOWS date shape uses, stated explicitly rather than taken
     * from a locale's CLDR data.
     *
     * <p>This is not defensive over-engineering — it fixes a shipped bug. The obvious spelling,
     * {@code DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK)}, renders September as
     * <strong>{@code Sept}</strong>, because four letters is what CLDR declares for en-GB. Every
     * other month is three letters either way, so the defect is invisible for eleven months of the
     * year and produced {@code 05 Sept 1975} where the contract's own date shape (and every
     * field-paths.yaml default, e.g. {@code 15 Jan 2026}) wants {@code 05 Sep 1975}. Switching to
     * {@code Locale.US} would also happen to work today, but it would leave the output hostage to
     * another locale's data changing under a JDK upgrade; an explicit map cannot drift.
     */
    private static final Map<Long, String> NOWS_MONTHS = Map.ofEntries(
            Map.entry(1L, "Jan"), Map.entry(2L, "Feb"), Map.entry(3L, "Mar"),
            Map.entry(4L, "Apr"), Map.entry(5L, "May"), Map.entry(6L, "Jun"),
            Map.entry(7L, "Jul"), Map.entry(8L, "Aug"), Map.entry(9L, "Sep"),
            Map.entry(10L, "Oct"), Map.entry(11L, "Nov"), Map.entry(12L, "Dec"));

    /** The NOWS date shape (e.g. {@code 22 Apr 1985}), not the request's ISO shape. */
    private static final DateTimeFormatter NOWS_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral(' ')
            .appendText(ChronoField.MONTH_OF_YEAR, NOWS_MONTHS)
            .appendLiteral(' ')
            .appendValue(ChronoField.YEAR, 4)
            .toFormatter(Locale.UK);

    private static final List<String> ADDRESS_KEYS = List.of("address1", "address2", "address3", "postcode");

    /**
     * @param defendantDetails the request's opaque {@code defendantDetails} map, possibly {@code null}
     * @return overlay keyed by NowsDataItems root property; empty when nothing maps
     */
    public Map<String, Object> from(final Map<String, Object> defendantDetails) {
        final Map<String, Object> overlay = new LinkedHashMap<>();
        if (defendantDetails != null) {
            final Map<String, Object> defendant = defendant(defendantDetails);
            if (!defendant.isEmpty()) {
                overlay.put("defendant", defendant);
            }
            // The account a NOW is raised against is the prosecutor's defendant id, not a number
            // the simulator makes up.
            text(defendantDetails, "prosecutorDefendantId")
                    .ifPresent(id -> overlay.put("accountNumber", id));
        }
        return overlay;
    }

    private Map<String, Object> defendant(final Map<String, Object> details) {
        final Map<String, Object> defendant = new LinkedHashMap<>();
        defName(details).ifPresent(value -> defendant.put("defName", value));
        dateOfBirth(details).ifPresent(value -> defendant.put("DoB", value));
        text(details, "nationalInsuranceNumber")
                .ifPresent(value -> defendant.put("nationalInsuranceNumber", value));
        text(details, "homeTelephoneNumber").ifPresent(value -> defendant.put("homeTelNo", value));
        text(details, "workTelephoneNumber").ifPresent(value -> defendant.put("businessTelNo", value));
        text(details, "mobileTelephoneNumber").ifPresent(value -> defendant.put("mobileTelNo", value));
        defAddress(details).ifPresent(value -> defendant.put("defAddress", value));
        return defendant;
    }

    private Optional<String> defName(final Map<String, Object> details) {
        final String forename = text(details, "forename").orElse("");
        final String surname = text(details, "surname").orElse("");
        final String defName = (forename + " " + surname).trim();
        return defName.isEmpty() ? Optional.empty() : Optional.of(defName);
    }

    private Optional<String> dateOfBirth(final Map<String, Object> details) {
        return text(details, "dateOfBirth").flatMap(this::asNowsDate);
    }

    private Optional<String> asNowsDate(final String isoDate) {
        Optional<String> formatted;
        try {
            formatted = Optional.of(NOWS_DATE.format(LocalDate.parse(isoDate)));
        } catch (final DateTimeParseException e) {
            log.warn("Ignoring an unparseable defendant dateOfBirth; omitting DoB from the response", e);
            formatted = Optional.empty();
        }
        return formatted;
    }

    /**
     * All four address properties are written whenever the caller supplied any of them, blank
     * where absent, so a partially addressed defendant still produces the complete address block
     * the NOWS renders rather than a ragged one.
     */
    private Optional<Object> defAddress(final Map<String, Object> details) {
        Optional<Object> defAddress = Optional.empty();
        if (ADDRESS_KEYS.stream().anyMatch(key -> text(details, key).isPresent())) {
            final Map<String, Object> lines = new LinkedHashMap<>();
            lines.put("defAddressLine1", text(details, "address1").orElse(""));
            lines.put("defAddressLine2", text(details, "address2").orElse(""));
            lines.put("defAddressLine3", text(details, "address3").orElse(""));
            lines.put("defPostcode", text(details, "postcode").orElse(""));
            defAddress = Optional.of(lines);
        }
        return defAddress;
    }

    private Optional<String> text(final Map<String, Object> details, final String key) {
        return Optional.ofNullable(details.get(key))
                .map(Object::toString)
                .filter(value -> !value.isBlank());
    }
}
