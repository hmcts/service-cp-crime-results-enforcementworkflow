package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code ImposingCourt} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImposingCourt(
        Integer ljaCode,
        String courtName) {
}
