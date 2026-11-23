package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Address;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;

/**
 * What the defendant's enforcement area is found by (CIMD-4640), as the NOWS functions find it for financial NOWs: the
 * defendant's postcode, else the national court code of the hearing court's LJA. Either may be null. {@link #toString}
 * leaves the postcode out (FR-017).
 */
public record EnforcementAreaQuery(String postcode, String ljaCode) {

    public static EnforcementAreaQuery from(final HearingResultedEvent event, final Defendant defendant) {
        final HearingResultedEvent.CourtCentre courtCentre = event.hearing() == null ? null : event.hearing().courtCentre();
        final String ljaCode = courtCentre == null || courtCentre.lja() == null ? null : courtCentre.lja().ljaCode();
        return new EnforcementAreaQuery(postcodeOf(defendant), ljaCode);
    }

    private static String postcodeOf(final Defendant defendant) {
        Address address = null;
        if (defendant.personDefendant() != null && defendant.personDefendant().personDetails() != null) {
            address = defendant.personDefendant().personDetails().address();
        } else if (defendant.legalEntityDefendant() != null && defendant.legalEntityDefendant().organisation() != null) {
            address = defendant.legalEntityDefendant().organisation().address();
        }
        return address == null ? null : address.postcode();
    }

    @Override
    public String toString() {
        return "EnforcementAreaQuery[postcode " + (postcode == null ? "absent" : "present") + ", ljaCode=" + ljaCode + "]";
    }
}
