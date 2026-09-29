package uk.gov.hmcts.cp.mapper;

/** Why a hearing-result submission could not be built. Recorded as MAPPING_FAILED (spec FR-013, "unsendable"). */
public enum MappingFailureReason {
    CASE_URN_INVALID,
    PROSECUTOR_DEFENDANT_ID_MISSING,
    ADDRESS1_MISSING,
    DATE_OF_HEARING_MISSING,
    COURT_HEARING_LOCATION_INVALID,
    PAYMENT_DUE_DATE_UNAVAILABLE,
    ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET
}
