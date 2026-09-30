package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mirrors {@code HearingResultedResponse} in libra-gateway-hearing-events-v0.6.0.yml.
 * {@code correlationId} is omitted from the JSON body (rather than serialised as {@code null})
 * when the caller supplied none, since the schema does not require it. The same applies to
 * {@code nowsDataItems} (optional since v0.6.0) when the caller requested no NOWS entities.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HearingResultedResponse(
        String caseUrn,
        String timestamp,
        String correlationId,
        NowsDataItems nowsDataItems) {
}
