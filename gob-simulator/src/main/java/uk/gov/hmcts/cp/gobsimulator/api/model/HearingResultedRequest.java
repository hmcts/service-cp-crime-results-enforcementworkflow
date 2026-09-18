package uk.gov.hmcts.cp.gobsimulator.api.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Only the parts of HearingResultedRequest the simulator reads are modelled; the remaining
 * mandatory objects are accepted as opaque maps so a contract-valid request is never rejected.
 *
 * <p>{@code NowsDataItemRequest.name} is structurally validated here ({@code @NotBlank} only) —
 * whether it is one of the contract's 12 permitted values is a semantic check the controller
 * makes against {@link uk.gov.hmcts.cp.gobsimulator.catalogue.Catalogue}, the single source of
 * truth for that enumeration.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record HearingResultedRequest(
        @NotBlank String caseUrn,
        @NotBlank String dateOfHearing,
        @NotBlank String courtHearingLocation,
        @NotNull Map<String, Object> defendantDetails,
        Map<String, Object> employerDetails,
        Map<String, Object> parentGuardianDetails,
        @NotNull Map<String, Object> paymentTerms,
        @NotNull Map<String, Object> enforcement,
        @NotEmpty List<HearingResult> results,
        @NotNull NowsDataRequest nowsDataRequest) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HearingResult(@NotBlank String resultCode, Number enforcerCode, Number jailDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataRequest(@NotEmpty List<NowsDataItemRequest> nowsDataItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataItemRequest(@NotBlank String name) {
    }
}
