package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HearingConfirmedRequest(
        @NotBlank String caseUrn,
        @NotBlank String courtHearingLocation,
        String dateOfHearing,
        String timeOfHearing) {
}
