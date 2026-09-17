package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code ParentGuardian} in libra-gateway-hearing-events-openapi-v0.3.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParentGuardian(
        String parentToPayFlag,
        String parentGuardianName,
        ParentGuardianAddress parentGuardianAddress) {
}
