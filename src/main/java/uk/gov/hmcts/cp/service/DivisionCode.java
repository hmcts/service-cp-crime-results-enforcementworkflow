package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;

/**
 * Division Code (feature 005, research.md R6): a prefix of {@code relatedReferenceNumber}, parsed as an integer
 * (not string comparison - the Confiscation set mixes 2/3-digit values, e.g. {@code "0100104567C"} -&gt; {@code "010"}
 * -&gt; {@code 10}). Not a new field: it is derived locally by CP, never looked up or returned by
 * {@code enforcement_area} (plan.md D1, D5 - the exact prefix length, assumed 3 characters, is unconfirmed against
 * CIMD-4172).
 */
public final class DivisionCode {

    /* default */ static final int PREFIX_LENGTH = 3;

    private final String code;
    private final int parsedValue;

    private DivisionCode(final String code, final int parsedValue) {
        this.code = code;
        this.parsedValue = parsedValue;
    }

    public static DivisionCode from(final String relatedReferenceNumber) {
        if (relatedReferenceNumber == null || relatedReferenceNumber.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("relatedReferenceNumber is too short to derive a Division Code: "
                    + relatedReferenceNumber);
        }
        final String prefix = relatedReferenceNumber.substring(0, PREFIX_LENGTH);
        final int parsed;
        try {
            parsed = Integer.parseInt(prefix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Division Code prefix is not numeric: " + prefix, e);
        }
        return new DivisionCode(prefix, parsed);
    }

    /** As stored on the row - the original, zero-padded string prefix (not the parsed integer). */
    public String value() {
        return code;
    }

    /** The parsed integer form - the {@code enforcement_area} lookup key. */
    public int asInt() {
        return parsedValue;
    }

    /** Enforcement vs. Confiscation (FR-003): computed from the configured Confiscation set, never trusted elsewhere. */
    public Area areaFor(final ResultNotificationProperties properties) {
        return properties.getConfiscationDivisionCodes().contains(parsedValue) ? Area.CONFISCATION : Area.ENFORCEMENT;
    }
}
