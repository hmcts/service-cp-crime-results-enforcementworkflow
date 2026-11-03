package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.ReferenceDataClient;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.event.HearingResultedEvent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.support.TestEvents.SC_TYPE;
import static uk.gov.hmcts.cp.support.TestEvents.WC_TYPE;
import static uk.gov.hmcts.cp.support.TestEvents.defendantOf;
import static uk.gov.hmcts.cp.support.TestEvents.result;
import static uk.gov.hmcts.cp.support.TestEvents.withResults;

class ResultCodeResolverTest {

    private static final UUID TFOUT_TYPE = UUID.randomUUID();
    private static final UUID WWDN_TYPE = UUID.randomUUID();
    private static final UUID BPOCRFSD_TYPE = UUID.randomUUID();
    private static final UUID PGPAY_TYPE = UUID.randomUUID();
    private static final UUID UNKNOWN_TYPE = UUID.randomUUID();
    private static final UUID LONG_TYPE = UUID.randomUUID();
    private static final UUID TEN_TYPE = UUID.randomUUID();

    private final ReferenceDataClient referenceData = mock(ReferenceDataClient.class);
    private final ResultCodeResolver resolver = new ResultCodeResolver(referenceData, new HearingResultProperties());

    @BeforeEach
    void stubReferenceData() {
        final Map<UUID, String> shortCodes = Map.of(SC_TYPE, "SC", WC_TYPE, "WC", TFOUT_TYPE, "TFOUT", WWDN_TYPE, "WWDN",
                BPOCRFSD_TYPE, "BPOCRFSD", PGPAY_TYPE, "PGPAY", LONG_TYPE, "ABCDEFGHIJK", TEN_TYPE, "ABCDEFGHIJ");
        when(referenceData.findShortCode(any(), any())).thenAnswer(inv -> Optional.ofNullable(shortCodes.get(inv.<UUID>getArgument(0))));
    }

    @Test
    void results_should_be_gathered_from_offence_defendant_case_and_hearing_levels() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE)), List.of(result(WC_TYPE)), List.of(result(TFOUT_TYPE)));

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.cpShortCodes()).containsExactly("SC", "WC", "TFOUT");
        assertThat(codes.gobCodes()).containsExactly("SC", "DW", "TFOOUT");
    }

    @Test
    void cp_codes_should_be_renamed_to_gob_codes() {
        final HearingResultedEvent event = withResults(List.of(result(WC_TYPE), result(TFOUT_TYPE), result(WWDN_TYPE)), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes())
                .containsExactly("DW", "TFOOUT", "WDN");
    }

    @Test
    void bpocrfsd_should_be_kept_as_a_distinct_code() {
        final HearingResultedEvent event = withResults(List.of(result(BPOCRFSD_TYPE)), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly("BPOCRFSD");
    }

    @Test
    // feature 003 FR-010 (2026-10-22, agreed with GOB/Libra): every code is sent, also codes GOB did not use before
    void codes_outside_the_old_gob_list_should_be_sent() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE), result(PGPAY_TYPE)), List.of(), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly("SC", "PGPAY");
        assertThat(codes.droppedCodes()).isEmpty();
        assertThat(codes.cpShortCodes()).containsExactly("SC", "PGPAY");
    }

    // the contract allows 10 characters; a longer code is never truncated (constitution VI): left out and reported
    @Test
    void code_longer_than_the_contract_allows_should_be_dropped_and_reported() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE), result(LONG_TYPE)), List.of(), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly("SC");
        assertThat(codes.droppedCodes()).containsExactly("longer-than-10:ABCDEFGHIJK");
    }

    @Test
    void code_of_exactly_ten_characters_should_be_sent() {
        final HearingResultedEvent event = withResults(List.of(result(TEN_TYPE)), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly("ABCDEFGHIJ");
    }

    @Test
    void duplicate_codes_should_be_collapsed() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE), result(SC_TYPE)), List.of(result(SC_TYPE)), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly("SC");
        assertThat(codes.cpShortCodes()).containsExactly("SC");
    }

    @Test
    void unknown_result_definition_should_be_dropped_and_reported() {
        final HearingResultedEvent event = withResults(List.of(result(UNKNOWN_TYPE), result(SC_TYPE)), List.of(), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly("SC");
        assertThat(codes.droppedCodes()).containsExactly("unknown-result-definition:" + UNKNOWN_TYPE);
    }

    @Test
    void lookup_should_fall_back_to_hearing_day_when_ordered_date_is_missing() {
        final HearingResultedEvent.JudicialResult undated =
                new HearingResultedEvent.JudicialResult(UUID.randomUUID(), SC_TYPE, "label", null, List.of(), null, null);
        final HearingResultedEvent event = withResults(List.of(undated), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly("SC");
        verify(referenceData).findShortCode(SC_TYPE, LocalDate.parse("2026-05-03")); // the event's hearingDay
    }

    // a later payload repeats earlier days' results: each is looked up on its own ordered date, not the share's hearing day
    @Test
    void lookup_should_use_the_ordered_date_of_the_result() {
        final HearingResultedEvent.JudicialResult earlier =
                new HearingResultedEvent.JudicialResult(UUID.randomUUID(), SC_TYPE, "label", LocalDate.parse("2026-04-20"), List.of(), null, null);
        final HearingResultedEvent event = withResults(List.of(earlier), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly("SC");
        verify(referenceData).findShortCode(SC_TYPE, LocalDate.parse("2026-04-20"));
    }

    // W9: a hearing-level result carrying no ids must not be counted for a defendant that also lacks one
    @Test
    void hearing_level_result_should_not_match_on_two_missing_ids() {
        final HearingResultedEvent base = withResults(List.of(result(SC_TYPE)), List.of(), List.of());
        final HearingResultedEvent.Defendant d = defendantOf(base);
        final HearingResultedEvent.Defendant noMasterId = new HearingResultedEvent.Defendant(d.id(), null,
                d.prosecutionAuthorityReference(), d.personDefendant(), d.legalEntityDefendant(),
                d.defendantCaseJudicialResults(), d.offences());
        final HearingResultedEvent.ProsecutionCase pc = base.hearing().prosecutionCases().getFirst();
        final HearingResultedEvent event = new HearingResultedEvent(new HearingResultedEvent.Hearing(base.hearing().id(),
                base.hearing().courtCentre(),
                List.of(new HearingResultedEvent.ProsecutionCase(pc.id(), pc.prosecutionCaseIdentifier(), List.of(noMasterId))),
                List.of(new HearingResultedEvent.DefendantJudicialResult(null, UUID.randomUUID(), result(WC_TYPE))),
                base.hearing().courtApplications()), base.isReshare(), base.sharedTime(), base.hearingDay());

        assertThat(resolver.resolve(event, noMasterId).cpShortCodes()).containsExactly("SC");
    }

    // feature 002 T045: each result keeps its offence (or none), ids, codes and adjournment marker
    @Test
    void each_result_should_keep_its_offence_ids_codes_and_adjournment_marker() {
        final HearingResultedEvent.JudicialResult adjournment = new HearingResultedEvent.JudicialResult(UUID.randomUUID(), PGPAY_TYPE,
                "next hearing", LocalDate.parse("2026-05-03"), List.of(), null, Map.of("listedStartDateTime", "x"));
        final HearingResultedEvent.JudicialResult offenceResult = result(SC_TYPE);
        final HearingResultedEvent.JudicialResult caseResult = result(WC_TYPE);
        final HearingResultedEvent event = withResults(List.of(offenceResult, adjournment), List.of(caseResult), List.of());
        final UUID offenceId = defendantOf(event).offences().getFirst().id();

        final List<ResolvedResult> results = resolver.resolve(event, defendantOf(event)).results();

        assertThat(results).containsExactly(
                new ResolvedResult(offenceId, offenceResult.judicialResultId(), SC_TYPE, "SC", "SC", false,
                        LocalDate.parse("2026-05-03")),
                new ResolvedResult(offenceId, adjournment.judicialResultId(), PGPAY_TYPE, "PGPAY", "PGPAY", true, LocalDate.parse("2026-05-03")),
                new ResolvedResult(null, caseResult.judicialResultId(), WC_TYPE, "WC", "DW", false, LocalDate.parse("2026-05-03")));
    }
}
