package uk.gov.hmcts.cp.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** {@code lja} is enriched on hearing results (core {@code courtCentre.json}); its code finds the enforcement area (CIMD-4640). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtCentre(String code, Lja lja) {

        public CourtCentre(final String code) {
            this(code, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lja(String ljaCode) {
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

    /**
     * {@code offenceTitle} and {@code offenceDetails} (feature 005, research.md R7a) are additive: the upstream wire
     * payload already carries {@code offenceTitle}/{@code wording} (confirmed in
     * {@code wiremock/mappings/results-hearing-details.json}), silently dropped until now by this record's narrower
     * field set. Shared with the GOB pipeline; existing consumers are unaffected since nothing is renamed or removed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Offence(UUID id, List<JudicialResult> judicialResults, String offenceTitle,
                          @JsonProperty("wording") String offenceDetails) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefendantJudicialResult(UUID masterDefendantId, UUID defendantId, JudicialResult judicialResult) {
    }

    /**
     * {@code judicialResultTypeId} is the reference-data result definition id used to look up the shortCode (R4).
     * {@code isAdjournmentResult} and {@code nextHearing} (kept only to know whether it is present) mark an adjournment:
     * an adjourned offence is not sent to GOB (feature 002 FR-018, research R19).
     */
    /**
     * {@code resultLabel} (feature 005, research.md R7a) is additive: sourced from the wire's per-result
     * {@code resultText} (the richer, instance-specific description - preferred over the already-captured generic
     * {@code label} for a notification email), currently silently dropped.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudicialResult(UUID judicialResultId, UUID judicialResultTypeId, String label, LocalDate orderedDate,
                                 List<JudicialResultPrompt> judicialResultPrompts, Boolean isAdjournmentResult,
                                 Object nextHearing, @JsonProperty("resultText") String resultLabel) {

        public boolean hasAdjournmentMarker() {
            return Boolean.TRUE.equals(isAdjournmentResult) || nextHearing != null;
        }
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
