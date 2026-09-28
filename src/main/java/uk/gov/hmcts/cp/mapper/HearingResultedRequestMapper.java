package uk.gov.hmcts.cp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.config.PaymentDueDateFallback;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Address;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Contact;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.PersonDetails;
import uk.gov.hmcts.cp.openapi.model.DefendantDetails;
import uk.gov.hmcts.cp.openapi.model.EnforcementDetails;
import uk.gov.hmcts.cp.openapi.model.HearingResult;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.NowsDataRequest;
import uk.gov.hmcts.cp.openapi.model.PaymentTerms;
import uk.gov.hmcts.cp.service.ResolvedCodes;
import uk.gov.hmcts.cp.service.SelectionResult;

import java.util.Optional;

/**
 * Builds the iteration-1 minimum {@code HearingResultedRequest} (data-model.md §2).
 * <ul>
 *   <li>{@code caseUrn} and {@code prosecutorDefendantId} are passed through unchanged. Only their
 *       presence and length (≤ 36) are checked, and a failure makes the submission unsendable
 *       (constitution Principle VI).</li>
 *   <li>Names and addresses are truncated to GOB's limits.</li>
 *   <li>{@code nowsDataRequest} is set only when at least one NOWS item is needed (R7).</li>
 *   <li>With no payment result, {@code paymentDueDate} follows {@code payment-due-date-fallback}
 *       (R13): default NONE (unsendable).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class HearingResultedRequestMapper {

    private static final int IDENTIFIER_MAX = 36;
    private static final int NAME_MAX = 35;
    private static final int ADDRESS_MAX = 35;
    private static final int POSTCODE_MAX = 8;
    private static final int TELEPHONE_MAX = 35;
    private static final int NINO_MAX = 9;
    private static final int COURT_HEARING_LOCATION_MAX = 7;

    private final HearingResultProperties properties;

    public MappingResult map(final HearingResultedEvent event, final SelectionResult.Selected selection,
                             final ResolvedCodes codes, final Optional<NowsDataRequest> nowsDataRequest) {
        final String caseUrn = selection.prosecutionCase().prosecutionCaseIdentifier().caseURN();
        final Defendant defendant = selection.defendant();
        final String courtHearingLocation = event.hearing().courtCentre() != null ? event.hearing().courtCentre().code() : null;
        final MappingResult result;
        if (isBlank(caseUrn) || caseUrn.length() > IDENTIFIER_MAX) {
            result = failed(MappingFailureReason.CASE_URN_INVALID, "caseUrn blank or longer than " + IDENTIFIER_MAX);
        } else if (isBlank(defendant.prosecutionAuthorityReference()) || defendant.prosecutionAuthorityReference().length() > IDENTIFIER_MAX) {
            result = failed(MappingFailureReason.PROSECUTOR_DEFENDANT_ID_MISSING,
                    "defendant prosecutionAuthorityReference blank or longer than " + IDENTIFIER_MAX);
        } else if (defendant.personDefendant() == null && defendant.legalEntityDefendant() != null) {
            result = failed(MappingFailureReason.ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET, "organisation defendant (CIMD-4248)");
        } else if (addressOf(defendant) == null || isBlank(addressOf(defendant).address1())) {
            result = failed(MappingFailureReason.ADDRESS1_MISSING, "defendant address1 missing");
        } else if (event.hearingDay() == null) {
            result = failed(MappingFailureReason.DATE_OF_HEARING_MISSING, "event hearingDay missing");
        } else if (isBlank(courtHearingLocation) || courtHearingLocation.length() > COURT_HEARING_LOCATION_MAX) {
            result = failed(MappingFailureReason.COURT_HEARING_LOCATION_INVALID,
                    "hearing courtCentre.code missing or longer than " + COURT_HEARING_LOCATION_MAX);
        } else if (properties.getPaymentDueDateFallback() == PaymentDueDateFallback.NONE) {
            // Iteration 1 does not yet map payment terms results (CIMD-4251, tasks.md T069), so no paymentDueDate source exists
            result = failed(MappingFailureReason.PAYMENT_DUE_DATE_UNAVAILABLE,
                    "paymentDueDate has no source: payment terms mapping not yet implemented (CIMD-4251) and "
                            + "payment-due-date-fallback=NONE (research.md R13)");
        } else {
            result = new MappingResult.Mapped(build(event, caseUrn, courtHearingLocation, defendant, codes, nowsDataRequest));
        }
        return result;
    }

    private static HearingResultedRequest build(final HearingResultedEvent event, final String caseUrn, final String courtHearingLocation,
                                                final Defendant defendant, final ResolvedCodes codes,
                                                final Optional<NowsDataRequest> nowsDataRequest) {
        final HearingResultedRequest request = new HearingResultedRequest()
                .caseUrn(caseUrn)
                .dateOfHearing(event.hearingDay())
                .courtHearingLocation(courtHearingLocation)
                .defendantDetails(defendantDetails(defendant))
                // HEARING_DATE placeholder until the BA/GOB ruling (R13); only reached when explicitly enabled
                .paymentTerms(new PaymentTerms()
                        .paymentDueDate(event.hearingDay())
                        .paymentCardRequested(PaymentTerms.PaymentCardRequestedEnum.N)
                        .parentToPay(PaymentTerms.ParentToPayEnum.N))
                .enforcement(new EnforcementDetails().prisonSentenceIndicator(EnforcementDetails.PrisonSentenceIndicatorEnum.N));
        codes.gobCodes().forEach(code -> request.addResultsItem(new HearingResult().resultCode(code)));
        nowsDataRequest.ifPresent(request::nowsDataRequest);
        return request;
    }

    private static DefendantDetails defendantDetails(final Defendant defendant) {
        final PersonDetails person = defendant.personDefendant().personDetails();
        final Address address = person.address();
        final Contact contact = person.contact() != null ? person.contact() : new Contact(null, null, null);
        final String nino = person.nationalInsuranceNumber();
        return new DefendantDetails()
                .prosecutorDefendantId(defendant.prosecutionAuthorityReference())
                .forename(Truncate.toMaxLength(person.firstName(), NAME_MAX))
                .surname(Truncate.toMaxLength(person.lastName(), NAME_MAX))
                .dateOfBirth(person.dateOfBirth())
                .nationalInsuranceNumber(withinLimit(nino, NINO_MAX))
                .address1(Truncate.toMaxLength(address.address1(), ADDRESS_MAX))
                .address2(Truncate.toMaxLength(address.address2(), ADDRESS_MAX))
                .address3(Truncate.toMaxLength(address.address3(), ADDRESS_MAX))
                .address4(Truncate.toMaxLength(address.address4(), ADDRESS_MAX))
                .address5(Truncate.toMaxLength(address.address5(), ADDRESS_MAX))
                .postcode(Truncate.toMaxLength(address.postcode(), POSTCODE_MAX))
                // a shortened number is a wrong number: an over-length value is left out, never truncated (data-model.md §2)
                .homeTelephoneNumber(withinLimit(contact.home(), TELEPHONE_MAX))
                .workTelephoneNumber(withinLimit(contact.work(), TELEPHONE_MAX))
                .mobileTelephoneNumber(withinLimit(contact.mobile(), TELEPHONE_MAX));
    }

    private static Address addressOf(final Defendant defendant) {
        return defendant.personDefendant() == null || defendant.personDefendant().personDetails() == null
                ? null : defendant.personDefendant().personDetails().address();
    }

    private static String withinLimit(final String value, final int maxLength) {
        return value != null && value.length() <= maxLength ? value : null;
    }

    private static MappingResult failed(final MappingFailureReason reason, final String detail) {
        return new MappingResult.Failed(reason, detail);
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
