package uk.gov.hmcts.cp.mapper;

/** Why a hearing-result submission could not be built. Recorded as MAPPING_FAILED (spec FR-013, "unsendable"). */
public enum MappingFailureReason {
    CASE_URN_INVALID,
    PROSECUTOR_DEFENDANT_ID_MISSING,
    ADDRESS1_MISSING,
    DATE_OF_HEARING_MISSING,
    COURT_HEARING_LOCATION_INVALID,
    /** A required payment terms prompt is missing or blank (feature 004, research R5). */
    PAYMENT_TERMS_INCOMPLETE,
    /** A payment terms value cannot be interpreted, or its prompt references are not configured (R5). */
    PAYMENT_TERMS_UNREADABLE,
    /** Several payment terms results give different blocks (R7, OQ-2 default). */
    PAYMENT_TERMS_CONFLICTING,
    ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET
}
