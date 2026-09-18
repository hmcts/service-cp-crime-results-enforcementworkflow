package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Transaction} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Transaction(
        String accountDate,
        String accountEventCode,
        String accountEventDetails) {
}
