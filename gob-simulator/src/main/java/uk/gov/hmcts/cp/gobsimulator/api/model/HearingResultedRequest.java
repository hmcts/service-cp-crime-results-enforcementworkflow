package uk.gov.hmcts.cp.gobsimulator.api.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
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
 *
 * <p>{@code results} and {@code nowsDataRequest} carry cascading validation so their nested
 * constraints ({@code HearingResult.resultCode}'s {@code @NotBlank}, {@code
 * NowsDataRequest.nowsDataItems}'s {@code @NotEmpty}, and — one level deeper, so {@code
 * NowsDataRequest.nowsDataItems} cascades too — {@code NowsDataItemRequest.name}'s {@code
 * @NotBlank}) actually run. Jakarta Bean Validation never cascades into a nested type without
 * {@code @Valid} somewhere on the path to it; without it these three constraints are silently
 * dead, and a malformed {@code results}/{@code nowsDataRequest} entry reaches the assembler and
 * surfaces as a 500 instead of the 400 the contract expects for a client input mistake. For the
 * two {@code List} fields, {@code @Valid} is placed on the type argument
 * ({@code List<@Valid X>}) rather than the container, per Jakarta Validation's own container-
 * element-constraint style (annotating the container directly still works but is deprecated and
 * logs an HV000271 warning on every startup — undesirable given mandatory JSON logging to
 * stdout).
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
        @NotEmpty List<@Valid HearingResult> results,
        @Valid @NotNull NowsDataRequest nowsDataRequest) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HearingResult(@NotBlank String resultCode, Number enforcerCode, Number jailDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataRequest(@NotEmpty List<@Valid NowsDataItemRequest> nowsDataItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NowsDataItemRequest(@NotBlank String name) {
    }
}
