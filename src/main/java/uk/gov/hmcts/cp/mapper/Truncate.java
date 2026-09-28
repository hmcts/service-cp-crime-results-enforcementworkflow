package uk.gov.hmcts.cp.mapper;

/** Shortens descriptive fields (names, addresses) to GOB's limits. Never used for identifiers (constitution Principle VI). */
public final class Truncate {

    private Truncate() {
    }

    public static String toMaxLength(final String value, final int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
