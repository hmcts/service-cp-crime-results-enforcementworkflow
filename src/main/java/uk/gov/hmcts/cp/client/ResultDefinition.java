package uk.gov.hmcts.cp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The only field this service reads from reference data's {@code get-result-definition} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultDefinition(String shortCode) {
}
