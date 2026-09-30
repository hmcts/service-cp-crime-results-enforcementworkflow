package uk.gov.hmcts.cp.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Minimal projection of the {@code public.events.hearing.hearing-resulted} public event (core
 * {@code hearing.json}). It keeps only the fields this service reads (data-model.md §1). Unknown
 * properties are ignored because the real event carries far more data than this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// isReshare is a Boolean, not a boolean: a missing flag must not read as "first share" (EnforcementCaseSelector)
public record HearingResultedEvent(Hearing hearing, Boolean isReshare, Instant sharedTime, LocalDate hearingDay) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hearing(UUID id, CourtCentre courtCentre, List<ProsecutionCase> prosecutionCases,
                          List<DefendantJudicialResult> defendantJudicialResults,
                          List<CourtApplication> courtApplications) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtCentre(String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProsecutionCase(UUID id, ProsecutionCaseIdentifier prosecutionCaseIdentifier, List<Defendant> defendants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProsecutionCaseIdentifier(String caseURN, String prosecutionAuthorityOUCode) {
    }

    /**
     * {@code prosecutionAuthorityReference} is the defendant-level reference: the GoB account number
     * supplied to the civil API as {@code prosecutorDefendantId} (research.md R9). It is not the
     * case-level {@code prosecutionCaseIdentifier.prosecutionAuthorityReference}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Defendant(UUID id, UUID masterDefendantId, String prosecutionAuthorityReference,
                            PersonDefendant personDefendant, LegalEntityDefendant legalEntityDefendant,
                            List<JudicialResult> defendantCaseJudicialResults, List<Offence> offences) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonDefendant(PersonDetails personDetails) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonDetails(String firstName, String lastName, LocalDate dateOfBirth, String nationalInsuranceNumber,
                                Address address, Contact contact) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LegalEntityDefendant(Organisation organisation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Organisation(String name, Address address, Contact contact) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String address1, String address2, String address3, String address4, String address5,
                          String postcode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String home, String work, String mobile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Offence(UUID id, List<JudicialResult> judicialResults) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefendantJudicialResult(UUID masterDefendantId, UUID defendantId, JudicialResult judicialResult) {
    }

    /** {@code judicialResultTypeId} is the reference-data result definition id used to look up the shortCode (R4). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudicialResult(UUID judicialResultId, UUID judicialResultTypeId, String label, LocalDate orderedDate,
                                 List<JudicialResultPrompt> judicialResultPrompts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudicialResultPrompt(String promptReference, String label, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtApplication(UUID id, List<CourtApplicationCase> courtApplicationCases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtApplicationCase(UUID prosecutionCaseId) {
    }
}
