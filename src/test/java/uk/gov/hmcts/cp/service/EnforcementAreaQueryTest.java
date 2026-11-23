package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Address;
import uk.gov.hmcts.cp.event.HearingResultedEvent.CourtCentre;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.LegalEntityDefendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Lja;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Organisation;
import uk.gov.hmcts.cp.support.Fixtures;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CIMD-4640: what the enforcement area is found by, read from the hearing's results. */
class EnforcementAreaQueryTest {

    private final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");

    @Test
    void person_defendant_should_be_found_by_their_postcode_and_the_courts_lja() {
        final Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();

        assertThat(EnforcementAreaQuery.from(event, defendant)).isEqualTo(new EnforcementAreaQuery("ZZ99 9ZZ", "9999"));
    }

    @Test
    void organisation_defendant_should_be_found_by_its_postcode() {
        final Defendant organisation = new Defendant(UUID.randomUUID(), UUID.randomUUID(), "1234567890", null,
                new LegalEntityDefendant(new Organisation("Example Ltd", new Address("1 Road", null, null, null, null, "XX1 1XX"), null)),
                List.of(), List.of());

        assertThat(EnforcementAreaQuery.from(event, organisation).postcode()).isEqualTo("XX1 1XX");
    }

    @Test
    void missing_address_and_lja_should_give_nulls() {
        final Defendant noAddress = new Defendant(UUID.randomUUID(), UUID.randomUUID(), "1234567890", null, null, List.of(), List.of());
        final HearingResultedEvent noLja = new HearingResultedEvent(new HearingResultedEvent.Hearing(event.hearing().id(),
                new CourtCentre("B01LY00"), event.hearing().prosecutionCases(), List.of(), List.of()),
                false, event.sharedTime(), event.hearingDay());

        assertThat(EnforcementAreaQuery.from(noLja, noAddress)).isEqualTo(new EnforcementAreaQuery(null, null));
        assertThat(new EnforcementAreaQuery(null, null).ljaCode()).isNull();
        assertThat(new CourtCentre("B01LY00", new Lja("9999")).lja().ljaCode()).isEqualTo("9999");
    }

    // FR-017: the postcode is never written out
    @Test
    void to_string_should_leave_the_postcode_out() {
        assertThat(new EnforcementAreaQuery("XX1 1XX", "9999").toString()).doesNotContain("XX1").contains("present", "9999");
        assertThat(new EnforcementAreaQuery(null, "9999").toString()).contains("absent");
    }
}
