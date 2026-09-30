package uk.gov.hmcts.cp.mapper;

/** Shortens descriptive fields (names, addresses) to GOB's limits. Never used for identifiers (constitution Principle VI). */
public final class Truncate {

    private Truncate() {
    }

    /** Cuts to {@code maxLength} characters (code points, as the schemas' maxLength counts), never splitting one. */
    public static String toMaxLength(final String value, final int maxLength) {
        return value == null || value.codePointCount(0, value.length()) <= maxLength
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
