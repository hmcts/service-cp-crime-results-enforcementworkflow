package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code WarrantContactDetails} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WarrantContactDetails(
        String warrantContactDetailsLine1,
        String warrantContactDetailsLine2,
        String warrantContactDetailsLine3,
        String warrantContactDetailsLine4,
        String warrantContactDetailsLine5) {
}
