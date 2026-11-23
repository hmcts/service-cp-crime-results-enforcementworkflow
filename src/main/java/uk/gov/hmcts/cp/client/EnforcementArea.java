package uk.gov.hmcts.cp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The only field this service reads from reference data's {@code referencedata.query.enforcement-area} response (CIMD-4640). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnforcementArea(Integer accountDivisionCode) {
}
