package uk.gov.hmcts.cp.support;

import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.DefendantJudicialResult;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResult;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Offence;
import uk.gov.hmcts.cp.event.HearingResultedEvent.ProsecutionCase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Builds variations of the enforcement fixture for unit tests. */
public final class TestEvents {

    public static final UUID SC_TYPE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID WC_TYPE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TestEvents() {
    }

    public static JudicialResult result(final UUID typeId) {
        return new JudicialResult(UUID.randomUUID(), typeId, "label", LocalDate.parse("2026-05-03"), List.of());
    }

    /** The enforcement fixture with the defendant's results replaced at each of the three levels. */
    public static HearingResultedEvent withResults(final List<JudicialResult> offenceResults,
                                                   final List<JudicialResult> defendantCaseResults,
                                                   final List<JudicialResult> hearingDefendantResults) {
        final HearingResultedEvent base = Fixtures.event("hearing-resulted-enforcement.json");
        final ProsecutionCase prosecutionCase = base.hearing().prosecutionCases().getFirst();
        final Defendant d = prosecutionCase.defendants().getFirst();
        final Offence offence = new Offence(d.offences().getFirst().id(), offenceResults);
        final Defendant defendant = new Defendant(d.id(), d.masterDefendantId(), d.prosecutionAuthorityReference(),
                d.personDefendant(), d.legalEntityDefendant(), defendantCaseResults, List.of(offence));
        final List<DefendantJudicialResult> hearingLevel = hearingDefendantResults.stream()
                .map(r -> new DefendantJudicialResult(d.masterDefendantId(), d.id(), r))
                .toList();
        final HearingResultedEvent.Hearing hearing = new HearingResultedEvent.Hearing(base.hearing().id(),
                base.hearing().courtCentre(),
                List.of(new ProsecutionCase(prosecutionCase.id(), prosecutionCase.prosecutionCaseIdentifier(), List.of(defendant))),
                hearingLevel, base.hearing().courtApplications());
        return new HearingResultedEvent(hearing, base.isReshare(), base.sharedTime(), base.hearingDay());
    }

    public static Defendant defendantOf(final HearingResultedEvent event) {
        return event.hearing().prosecutionCases().getFirst().defendants().getFirst();
    }
}
