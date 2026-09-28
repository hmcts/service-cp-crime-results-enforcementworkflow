package uk.gov.hmcts.cp.mapper;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.config.PaymentDueDateFallback;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.PersonDetails;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.service.ResolvedCodes;
import uk.gov.hmcts.cp.service.SelectionResult;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HearingResultedRequestMapperTest {

    private static final ResolvedCodes SC_AND_WC = new ResolvedCodes(List.of("SC", "WC"),
            new LinkedHashSet<>(List.of(ResultCodeEnum.SC, ResultCodeEnum.DW)), List.of());

    @Test
    void enforcement_fixture_should_map_to_the_expected_minimum_payload() {
        final HearingResultedRequest request = mapped(Fixtures.event("hearing-resulted-enforcement.json"), PaymentDueDateFallback.HEARING_DATE);

        final JsonNode actual = PayloadJson.MAPPER.readTree(PayloadJson.MAPPER.writeValueAsString(request));
        final JsonNode expected = PayloadJson.MAPPER.readTree(Fixtures.json("expected/hearing-resulted-request-minimum.json"));
        assertThat(actual).isEqualTo(expected);
        assertThat(request.getNowsDataRequest()).isNull();
    }

    @Test
    void mapped_payload_should_be_valid_against_the_libra_contract() {
        final HearingResultedRequest request = mapped(Fixtures.event("hearing-resulted-enforcement.json"), PaymentDueDateFallback.HEARING_DATE);

        assertThat(LibraContract.hearingResultedRequestViolations(PayloadJson.MAPPER.writeValueAsString(request))).isEmpty();
    }

    @Test
    void schema_check_should_catch_an_invalid_payload() {
        assertThat(LibraContract.hearingResultedRequestViolations("{\"caseUrn\":\"E012345678\",\"nowsDataRequest\":{\"nowsDataItems\":[]}}"))
                .isNotEmpty();
    }

    @Test
    void identifiers_should_be_passed_through_unchanged() {
        final HearingResultedRequest request = mapped(withIdentifiers(" e0123-45678 ", " 12-34567890 "), PaymentDueDateFallback.HEARING_DATE);

        assertThat(request.getCaseUrn()).isEqualTo(" e0123-45678 ");
        assertThat(request.getDefendantDetails().getProsecutorDefendantId()).isEqualTo(" 12-34567890 ");
    }

    @Test
    void descriptive_fields_should_be_truncated_to_gob_limits() {
        final HearingResultedEvent event = withPerson("F".repeat(40), "S".repeat(40), "A".repeat(40), "N17 6RT XYZ");

        final HearingResultedRequest request = mapped(event, PaymentDueDateFallback.HEARING_DATE);

        assertThat(request.getDefendantDetails().getForename()).hasSize(35);
        assertThat(request.getDefendantDetails().getSurname()).hasSize(35);
        assertThat(request.getDefendantDetails().getAddress1()).hasSize(35);
        assertThat(request.getDefendantDetails().getPostcode()).isEqualTo("N17 6RT ");
    }

    @Test
    void safe_defaults_should_be_applied() {
        final HearingResultedRequest request = mapped(Fixtures.event("hearing-resulted-enforcement.json"), PaymentDueDateFallback.HEARING_DATE);

        assertThat(request.getPaymentTerms().getPaymentCardRequested().getValue()).isEqualTo("N");
        assertThat(request.getPaymentTerms().getParentToPay().getValue()).isEqualTo("N");
        assertThat(request.getEnforcement().getPrisonSentenceIndicator().getValue()).isEqualTo("N");
        assertThat(request.getPaymentTerms().getPaymentDueDate()).isEqualTo(request.getDateOfHearing());
    }

    @Test
    void case_urn_longer_than_36_should_fail_mapping() {
        assertFailure(withIdentifiers("E".repeat(37), "1234567890"), MappingFailureReason.CASE_URN_INVALID);
    }

    @Test
    void blank_case_urn_should_fail_mapping() {
        assertFailure(withIdentifiers("  ", "1234567890"), MappingFailureReason.CASE_URN_INVALID);
    }

    @Test
    void missing_account_number_should_fail_mapping() {
        assertFailure(Fixtures.event("hearing-resulted-missing-account-number.json"), MappingFailureReason.PROSECUTOR_DEFENDANT_ID_MISSING);
    }

    @Test
    void account_number_longer_than_36_should_fail_mapping() {
        assertFailure(withIdentifiers("E012345678", "9".repeat(37)), MappingFailureReason.PROSECUTOR_DEFENDANT_ID_MISSING);
    }

    @Test
    void missing_address1_should_fail_mapping() {
        assertFailure(withPerson("Edward", "Harrison", null, "N17 6RT"), MappingFailureReason.ADDRESS1_MISSING);
    }

    @Test
    void payment_due_date_fallback_none_should_fail_mapping() {
        final MappingResult result = mapper(PaymentDueDateFallback.NONE).map(Fixtures.event("hearing-resulted-enforcement.json"),
                select(Fixtures.event("hearing-resulted-enforcement.json")), SC_AND_WC, Optional.empty());

        assertThat(result).isInstanceOfSatisfying(MappingResult.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(MappingFailureReason.PAYMENT_DUE_DATE_UNAVAILABLE));
    }

    @Test
    void organisation_defendant_should_fail_mapping_until_cimd_4248() {
        assertFailure(Fixtures.event("hearing-resulted-organisation-defendant.json"),
                MappingFailureReason.ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET);
    }

    @Test
    void missing_court_centre_code_should_fail_mapping() {
        assertFailure(withCourtCentre(null), MappingFailureReason.COURT_HEARING_LOCATION_INVALID);
    }

    @Test
    void court_centre_code_longer_than_7_should_fail_mapping() {
        assertFailure(withCourtCentre(new HearingResultedEvent.CourtCentre("B01LY001")), MappingFailureReason.COURT_HEARING_LOCATION_INVALID);
    }

    @Test
    void missing_hearing_day_should_fail_mapping() {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        assertFailure(new HearingResultedEvent(base.hearing(), false, base.sharedTime(), null), MappingFailureReason.DATE_OF_HEARING_MISSING);
    }

    @Test
    void over_length_telephone_numbers_should_be_left_out_not_truncated() {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase pc = base.hearing().prosecutionCases().getFirst();
        final Defendant d = pc.defendants().getFirst();
        final PersonDetails p = d.personDefendant().personDetails();
        final PersonDetails person = new PersonDetails(p.firstName(), p.lastName(), p.dateOfBirth(), p.nationalInsuranceNumber(),
                p.address(), new HearingResultedEvent.Contact("0".repeat(36), null, "07700900123"));
        final Defendant defendant = new Defendant(d.id(), d.masterDefendantId(), d.prosecutionAuthorityReference(),
                new HearingResultedEvent.PersonDefendant(person), null, d.defendantCaseJudicialResults(), d.offences());

        final HearingResultedRequest request = mapped(replaceCase(base,
                new HearingResultedEvent.ProsecutionCase(pc.id(), pc.prosecutionCaseIdentifier(), List.of(defendant))), PaymentDueDateFallback.HEARING_DATE);

        assertThat(request.getDefendantDetails().getHomeTelephoneNumber()).isNull();
        assertThat(request.getDefendantDetails().getMobileTelephoneNumber()).isEqualTo("07700900123");
    }

    private static HearingResultedEvent withCourtCentre(final HearingResultedEvent.CourtCentre courtCentre) {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.Hearing h = base.hearing();
        return new HearingResultedEvent(new HearingResultedEvent.Hearing(h.id(), courtCentre, h.prosecutionCases(),
                h.defendantJudicialResults(), h.courtApplications()), false, base.sharedTime(), base.hearingDay());
    }

    private static void assertFailure(final HearingResultedEvent event, final MappingFailureReason reason) {
        final MappingResult result = mapper(PaymentDueDateFallback.HEARING_DATE).map(event, select(event), SC_AND_WC, Optional.empty());

        assertThat(result).isInstanceOfSatisfying(MappingResult.Failed.class, f -> {
            assertThat(f.reason()).isEqualTo(reason);
            assertThat(f.detail()).doesNotContain("Edward", "Harrison", "1 High Street"); // no PII in failure detail
        });
    }

    private static HearingResultedRequest mapped(final HearingResultedEvent event, final PaymentDueDateFallback fallback) {
        final MappingResult result = mapper(fallback).map(event, select(event), SC_AND_WC, Optional.empty());
        assertThat(result).isInstanceOf(MappingResult.Mapped.class);
        return ((MappingResult.Mapped) result).request();
    }

    private static HearingResultedRequestMapper mapper(final PaymentDueDateFallback fallback) {
        final HearingResultProperties properties = new HearingResultProperties();
        properties.setPaymentDueDateFallback(fallback);
        return new HearingResultedRequestMapper(properties);
    }

    private static SelectionResult.Selected select(final HearingResultedEvent event) {
        final HearingResultedEvent.ProsecutionCase prosecutionCase = event.hearing().prosecutionCases().getFirst();
        return new SelectionResult.Selected(prosecutionCase, prosecutionCase.defendants().getFirst());
    }

    private static HearingResultedEvent withIdentifiers(final String caseUrn, final String accountNumber) {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase pc = base.hearing().prosecutionCases().getFirst();
        final Defendant d = pc.defendants().getFirst();
        final Defendant defendant = new Defendant(d.id(), d.masterDefendantId(), accountNumber, d.personDefendant(),
                d.legalEntityDefendant(), d.defendantCaseJudicialResults(), d.offences());
        final HearingResultedEvent.ProsecutionCase prosecutionCase = new HearingResultedEvent.ProsecutionCase(pc.id(),
                new HearingResultedEvent.ProsecutionCaseIdentifier(caseUrn, pc.prosecutionCaseIdentifier().prosecutionAuthorityOUCode()),
                List.of(defendant));
        return replaceCase(base, prosecutionCase);
    }

    private static HearingResultedEvent withPerson(final String firstName, final String lastName, final String address1,
                                                   final String postcode) {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase pc = base.hearing().prosecutionCases().getFirst();
        final Defendant d = pc.defendants().getFirst();
        final PersonDetails p = d.personDefendant().personDetails();
        final HearingResultedEvent.Address a = p.address();
        final PersonDetails person = new PersonDetails(firstName, lastName, p.dateOfBirth(), p.nationalInsuranceNumber(),
                new HearingResultedEvent.Address(address1, a.address2(), a.address3(), a.address4(), a.address5(), postcode), p.contact());
        final Defendant defendant = new Defendant(d.id(), d.masterDefendantId(), d.prosecutionAuthorityReference(),
                new HearingResultedEvent.PersonDefendant(person), null, d.defendantCaseJudicialResults(), d.offences());
        return replaceCase(base, new HearingResultedEvent.ProsecutionCase(pc.id(), pc.prosecutionCaseIdentifier(), List.of(defendant)));
    }

    private static HearingResultedEvent replaceCase(final HearingResultedEvent base, final HearingResultedEvent.ProsecutionCase pc) {
        final HearingResultedEvent.Hearing h = base.hearing();
        return new HearingResultedEvent(new HearingResultedEvent.Hearing(h.id(), h.courtCentre(), List.of(pc),
                h.defendantJudicialResults(), h.courtApplications()), base.isReshare(), base.sharedTime(), base.hearingDay());
    }
}
