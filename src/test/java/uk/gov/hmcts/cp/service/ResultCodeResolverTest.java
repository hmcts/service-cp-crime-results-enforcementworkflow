package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.ReferenceDataClient;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

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

    private final ReferenceDataClient referenceData = mock(ReferenceDataClient.class);
    private final ResultCodeResolver resolver = new ResultCodeResolver(referenceData, new HearingResultProperties());

    @BeforeEach
    void stubReferenceData() {
        final Map<UUID, String> shortCodes = Map.of(SC_TYPE, "SC", WC_TYPE, "WC", TFOUT_TYPE, "TFOUT", WWDN_TYPE, "WWDN",
                BPOCRFSD_TYPE, "BPOCRFSD", PGPAY_TYPE, "PGPAY");
        when(referenceData.findShortCode(any(), any())).thenAnswer(inv -> Optional.ofNullable(shortCodes.get(inv.<UUID>getArgument(0))));
    }

    @Test
    void results_should_be_gathered_from_offence_defendant_case_and_hearing_levels() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE)), List.of(result(WC_TYPE)), List.of(result(TFOUT_TYPE)));

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.cpShortCodes()).containsExactly("SC", "WC", "TFOUT");
        assertThat(codes.gobCodes()).containsExactly(ResultCodeEnum.SC, ResultCodeEnum.DW, ResultCodeEnum.TFOOUT);
    }

    @Test
    void cp_codes_should_be_renamed_to_gob_codes() {
        final HearingResultedEvent event = withResults(List.of(result(WC_TYPE), result(TFOUT_TYPE), result(WWDN_TYPE)), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes())
                .containsExactly(ResultCodeEnum.DW, ResultCodeEnum.TFOOUT, ResultCodeEnum.WDN);
    }

    @Test
    void bpocrfsd_should_be_kept_as_a_distinct_code() {
        final HearingResultedEvent event = withResults(List.of(result(BPOCRFSD_TYPE)), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly(ResultCodeEnum.BPOCRFSD);
    }

    @Test
    void codes_outside_the_gob_enum_should_be_dropped_and_reported() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE), result(PGPAY_TYPE)), List.of(), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly(ResultCodeEnum.SC);
        assertThat(codes.droppedCodes()).containsExactly("PGPAY");
        assertThat(codes.cpShortCodes()).containsExactly("SC", "PGPAY");
    }

    @Test
    void duplicate_codes_should_be_collapsed() {
        final HearingResultedEvent event = withResults(List.of(result(SC_TYPE), result(SC_TYPE)), List.of(result(SC_TYPE)), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly(ResultCodeEnum.SC);
        assertThat(codes.cpShortCodes()).containsExactly("SC");
    }

    @Test
    void unknown_result_definition_should_be_dropped_and_reported() {
        final HearingResultedEvent event = withResults(List.of(result(UNKNOWN_TYPE), result(SC_TYPE)), List.of(), List.of());

        final ResolvedCodes codes = resolver.resolve(event, defendantOf(event));

        assertThat(codes.gobCodes()).containsExactly(ResultCodeEnum.SC);
        assertThat(codes.droppedCodes()).containsExactly("unknown-result-definition:" + UNKNOWN_TYPE);
    }

    @Test
    void lookup_should_fall_back_to_hearing_day_when_ordered_date_is_missing() {
        final HearingResultedEvent.JudicialResult undated =
                new HearingResultedEvent.JudicialResult(UUID.randomUUID(), SC_TYPE, "label", null, List.of());
        final HearingResultedEvent event = withResults(List.of(undated), List.of(), List.of());

        assertThat(resolver.resolve(event, defendantOf(event)).gobCodes()).containsExactly(ResultCodeEnum.SC);
        verify(referenceData).findShortCode(SC_TYPE, LocalDate.parse("2026-05-03")); // the event's hearingDay
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
}
