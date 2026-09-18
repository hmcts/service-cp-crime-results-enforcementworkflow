package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code DefAddress} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DefAddress(
        String defAddressLine1,
        String defAddressLine2,
        String defAddressLine3,
        String defPostcode) {
}
