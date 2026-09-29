package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;

import static org.assertj.core.api.Assertions.assertThat;

class EnforcementCaseSelectorTest {

    private final EnforcementCaseSelector selector = new EnforcementCaseSelector("GAPGD00");

    @Test
    void enforcement_case_should_be_selected_with_its_defendant() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");

        final SelectionResult result = selector.select(event);

        assertThat(result).isInstanceOfSatisfying(SelectionResult.Selected.class, selected -> {
            assertThat(selected.prosecutionCase().prosecutionCaseIdentifier().caseURN()).isEqualTo("E012345678");
            assertThat(selected.defendant().prosecutionAuthorityReference()).isEqualTo("1234567890");
        });
    }

    @Test
    void non_enforcement_case_should_be_skipped() {
        final SelectionResult result = selector.select(Fixtures.event("hearing-resulted-non-enforcement.json"));

        assertThat(result).isEqualTo(new SelectionResult.Skipped(SkipReason.NO_ENFORCEMENT_CASE));
    }

    @Test
    void authority_code_should_come_from_configuration() {
        final EnforcementCaseSelector otherAuthority = new EnforcementCaseSelector("GAFTL00");

        assertThat(otherAuthority.select(Fixtures.event("hearing-resulted-non-enforcement.json")))
                .isInstanceOf(SelectionResult.Selected.class);
        assertThat(otherAuthority.select(Fixtures.event("hearing-resulted-enforcement.json")))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.NO_ENFORCEMENT_CASE));
    }

    @Test
    void reshare_should_be_skipped_first_even_for_an_enforcement_case() {
        assertThat(selector.select(Fixtures.event("hearing-resulted-reshare.json")))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.RESHARE));
    }

    @Test
    void more_than_one_enforcement_case_should_be_skipped() {
        assertThat(selector.select(Fixtures.event("hearing-resulted-two-enforcement-cases.json")))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.MULTIPLE_ENFORCEMENT_CASES));
    }

    @Test
    void more_than_one_defendant_should_be_skipped() {
        assertThat(selector.select(Fixtures.event("hearing-resulted-two-defendants.json")))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.MULTIPLE_DEFENDANTS));
    }

    @Test
    void linked_application_should_be_skipped() {
        assertThat(selector.select(Fixtures.event("hearing-resulted-linked-application.json")))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.LINKED_APPLICATION));
    }

    @Test
    void enforcement_case_without_defendants_should_be_skipped_as_no_defendant() {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase pc = base.hearing().prosecutionCases().getFirst();
        final HearingResultedEvent event = new HearingResultedEvent(new HearingResultedEvent.Hearing(base.hearing().id(),
                base.hearing().courtCentre(), java.util.List.of(new HearingResultedEvent.ProsecutionCase(pc.id(),
                        pc.prosecutionCaseIdentifier(), java.util.List.of())), null, null), false, base.sharedTime(), base.hearingDay());

        assertThat(selector.select(event)).isEqualTo(new SelectionResult.Skipped(SkipReason.NO_DEFENDANT));
    }

    @Test
    void event_without_hearing_should_be_skipped() {
        assertThat(selector.select(new HearingResultedEvent(null, false, null, null)))
                .isEqualTo(new SelectionResult.Skipped(SkipReason.NO_ENFORCEMENT_CASE));
    }
}
