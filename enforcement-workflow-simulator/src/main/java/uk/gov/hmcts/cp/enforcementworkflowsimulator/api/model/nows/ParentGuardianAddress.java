package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code ParentGuardianAddress} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParentGuardianAddress(
        String parentGuardianAddressLine1,
        String parentGuardianAddressLine2,
        String parentGuardianAddressLine3) {
}
