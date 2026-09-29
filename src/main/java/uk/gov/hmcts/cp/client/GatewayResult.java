package uk.gov.hmcts.cp.client;

import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;

/** Outcome of one POST to the enforcement gateway. {@code rawResponse} is the gateway body as received, for persistence. */
public sealed interface GatewayResult {

    /** {@code response} is null when the 2xx body could not be parsed; {@code rawResponse} is kept regardless. */
    record Success(HearingResultedResponse response, String rawResponse, int httpStatus) implements GatewayResult {
    }

    /** {@code httpStatus} is null when no response was received; {@code detail} says whether it was sent at all (R20). */
    record Failure(Integer httpStatus, String detail) implements GatewayResult {
    }
}
