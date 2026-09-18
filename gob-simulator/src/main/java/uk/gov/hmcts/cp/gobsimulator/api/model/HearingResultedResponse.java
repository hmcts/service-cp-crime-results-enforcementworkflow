package uk.gov.hmcts.cp.gobsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mirrors {@code HearingResultedResponse} in libra-gateway-hearing-events-v0.4.0.yml.
 * {@code correlationId} is omitted from the JSON body (rather than serialised as {@code null})
 * when the caller supplied none, since the schema does not require it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HearingResultedResponse(
        String caseUrn,
        String timestamp,
        String correlationId,
        NowsDataItems nowsDataItems) {
}
