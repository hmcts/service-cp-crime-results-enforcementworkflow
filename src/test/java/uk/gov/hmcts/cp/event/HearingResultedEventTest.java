package uk.gov.hmcts.cp.event;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HearingResultedEventTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "hearing-resulted-enforcement.json",
        "hearing-resulted-reshare.json",
        "hearing-resulted-non-enforcement.json",
        "hearing-resulted-two-defendants.json",
        "hearing-resulted-two-enforcement-cases.json",
        "hearing-resulted-linked-application.json",
        "hearing-resulted-unknown-codes-only.json",
        "hearing-resulted-missing-account-number.json",
        "hearing-resulted-organisation-defendant.json"
    })
    void every_fixture_should_deserialise(final String fixture) {
        final HearingResultedEvent event = Fixtures.event(fixture);

        assertThat(event.hearing().id()).isNotNull();
        assertThat(event.hearingDay()).isEqualTo(LocalDate.parse("2026-05-03"));
        assertThat(event.hearing().prosecutionCases()).isNotEmpty();
    }

    @Test
    void enforcement_fixture_should_expose_the_fields_the_workflow_reads() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase prosecutionCase = event.hearing().prosecutionCases().getFirst();
        final HearingResultedEvent.Defendant defendant = prosecutionCase.defendants().getFirst();

        assertThat(event.isReshare()).isFalse();
        assertThat(event.sharedTime()).isEqualTo(Instant.parse("2026-05-03T14:30:00Z"));
        assertThat(event.hearing().courtCentre().code()).isEqualTo("B01LY00");
        assertThat(prosecutionCase.prosecutionCaseIdentifier().prosecutionAuthorityOUCode()).isEqualTo("GAPGD00");
        assertThat(prosecutionCase.prosecutionCaseIdentifier().caseURN()).isEqualTo("E012345678");
        assertThat(defendant.prosecutionAuthorityReference()).isEqualTo("1234567890");
        assertThat(defendant.personDefendant().personDetails().address().address1()).isEqualTo("1 High Street");
        assertThat(defendant.offences().getFirst().judicialResults())
                .extracting(HearingResultedEvent.JudicialResult::judicialResultTypeId)
                .containsExactly(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        UUID.fromString("22222222-2222-2222-2222-222222222222"));
    }

    @Test
    void reshare_fixture_should_set_is_reshare() {
        assertThat(Fixtures.event("hearing-resulted-reshare.json").isReshare()).isTrue();
    }

    @Test
    void linked_application_fixture_should_expose_application_case_link() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-linked-application.json");

        assertThat(event.hearing().courtApplications().getFirst().courtApplicationCases().getFirst().prosecutionCaseId())
                .isEqualTo(event.hearing().prosecutionCases().getFirst().id());
    }

    @Test
    void organisation_fixture_should_expose_legal_entity() {
        final HearingResultedEvent.Defendant defendant = Fixtures.event("hearing-resulted-organisation-defendant.json")
                .hearing().prosecutionCases().getFirst().defendants().getFirst();

        assertThat(defendant.personDefendant()).isNull();
        assertThat(defendant.legalEntityDefendant().organisation().name()).isEqualTo("Acme Haulage Ltd");
    }
}
