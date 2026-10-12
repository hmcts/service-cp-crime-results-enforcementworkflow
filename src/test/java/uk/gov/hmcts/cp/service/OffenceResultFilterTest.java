package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.entity.GobSentOffenceEntity;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;
import uk.gov.hmcts.cp.repository.GobSentOffenceRepository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Feature 002 FR-018, research R19/R20: each offence's final result, and each non-offence result, is sent once. */
class OffenceResultFilterTest {

    private static final UUID CASE_ID = UUID.randomUUID();
    private static final LocalDate DAY_2 = LocalDate.parse("2026-05-10");
    private static final LocalDate DAY_1 = LocalDate.parse("2026-05-03");
    private static final UUID OFFENCE_1 = UUID.randomUUID();
    private static final UUID OFFENCE_2 = UUID.randomUUID();

    private final GobSentOffenceRepository repository = mock(GobSentOffenceRepository.class);
    private final OffenceResultFilter filter = new OffenceResultFilter(repository, new HearingResultProperties());

    @Test
    void offence_already_sent_should_be_dropped_with_all_its_results_even_when_repeated() {
        sentOffences(OFFENCE_1);
        final ResolvedCodes codes = codes(sc(OFFENCE_1, DAY_1), result(OFFENCE_2, "DW", ResultCodeEnum.DW, false, DAY_2));

        final OffenceFilterResult result = filter.filter(CASE_ID, DAY_2, codes);

        assertThat(result.toSend().gobCodes()).containsExactly(ResultCodeEnum.DW);
        assertThat(result.sentItems()).containsExactly(SentItem.offence(OFFENCE_2));
        assertThat(result.offencesAlreadySent()).isEqualTo(1);
    }

    @Test
    void offence_adjourned_in_this_share_should_not_be_sent_even_with_a_gob_code() {
        final ResolvedCodes codes = codes(sc(OFFENCE_1, DAY_1), result(OFFENCE_2, "SUMM", ResultCodeEnum.SUMM, false, DAY_1),
                result(OFFENCE_2, "NEXH", null, true, DAY_1));

        final OffenceFilterResult result = filter.filter(CASE_ID, DAY_1, codes);

        assertThat(result.toSend().gobCodes()).containsExactly(ResultCodeEnum.SC);
        assertThat(result.sentItems()).containsExactly(SentItem.offence(OFFENCE_1));
        assertThat(result.offencesAdjourned()).isEqualTo(1);
    }

    @Test
    void adjournment_code_without_marker_should_also_adjourn_the_offence() {
        final ResolvedCodes codes = codes(result(OFFENCE_2, "ADJNN", ResultCodeEnum.ADJNN, false, DAY_1));

        final OffenceFilterResult result = filter.filter(CASE_ID, DAY_1, codes);

        assertThat(result.nothingToSend()).isTrue();
        assertThat(result.sentItems()).isEmpty();
    }

    // an earlier day's adjournment repeated in this payload does not stop the final result; the adjournment itself is never sent
    @Test
    void earlier_adjournment_repeated_in_the_payload_should_not_stop_the_final_result() {
        final ResolvedCodes codes = codes(result(OFFENCE_2, "ADJNN", ResultCodeEnum.ADJNN, false, DAY_1),
                result(OFFENCE_2, "SUMM", ResultCodeEnum.SUMM, false, DAY_2));

        final OffenceFilterResult result = filter.filter(CASE_ID, DAY_2, codes);

        assertThat(result.toSend().gobCodes()).containsExactly(ResultCodeEnum.SUMM);
        assertThat(result.sentItems()).containsExactly(SentItem.offence(OFFENCE_2));
    }

    @Test
    void offence_without_a_gob_code_should_not_be_recorded_as_sent() {
        final ResolvedCodes codes = codes(sc(OFFENCE_1, DAY_1), result(OFFENCE_2, "PGPAY", null, false, DAY_1));

        assertThat(filter.filter(CASE_ID, DAY_1, codes).sentItems()).containsExactly(SentItem.offence(OFFENCE_1));
    }

    @Test
    void non_offence_result_should_be_sent_once_by_judicial_result_id() {
        final ResolvedResult collectionOrder = result(null, "COLLO", ResultCodeEnum.COLLO, false, DAY_1);
        when(repository.findByCaseIdAndJudicialResultIdIn(eq(CASE_ID), any())).thenReturn(List.of());
        final OffenceFilterResult first = filter.filter(CASE_ID, DAY_1, codes(collectionOrder));

        when(repository.findByCaseIdAndJudicialResultIdIn(eq(CASE_ID), any())).thenReturn(List.of(sent(null, collectionOrder.judicialResultId())));
        final OffenceFilterResult again = filter.filter(CASE_ID, DAY_2, codes(collectionOrder));

        assertThat(first.sentItems()).containsExactly(SentItem.judicialResult(collectionOrder.judicialResultId()));
        assertThat(again.nothingToSend()).isTrue();
        assertThat(again.resultsAlreadySent()).isEqualTo(1);
    }

    @Test
    void everything_already_sent_should_leave_nothing_to_send() {
        sentOffences(OFFENCE_1, OFFENCE_2);

        final OffenceFilterResult result = filter.filter(CASE_ID, DAY_2, codes(sc(OFFENCE_1, DAY_1), sc(OFFENCE_2, DAY_1)));

        assertThat(result.nothingToSend()).isTrue();
        assertThat(result.summary()).isEqualTo("offences already sent 2, offences adjourned 0, other results already sent 0");
    }

    private void sentOffences(final UUID... offenceIds) {
        when(repository.findByCaseIdAndOffenceIdIn(eq(CASE_ID), any()))
                .thenReturn(Arrays.stream(offenceIds).map(id -> sent(id, null)).toList());
    }

    private static GobSentOffenceEntity sent(final UUID offenceId, final UUID judicialResultId) {
        final GobSentOffenceEntity entity = new GobSentOffenceEntity();
        entity.setOffenceId(offenceId);
        entity.setJudicialResultId(judicialResultId);
        return entity;
    }

    private static ResolvedResult sc(final UUID offenceId, final LocalDate orderedDate) {
        return result(offenceId, "SC", ResultCodeEnum.SC, false, orderedDate);
    }

    private static ResolvedResult result(final UUID offenceId, final String shortCode, final ResultCodeEnum gobCode,
                                         final boolean adjournmentMarker, final LocalDate orderedDate) {
        return new ResolvedResult(offenceId, UUID.randomUUID(), UUID.randomUUID(), shortCode, gobCode, adjournmentMarker, orderedDate);
    }

    private static ResolvedCodes codes(final ResolvedResult... results) {
        return ResolvedCodes.fromResults(List.of(results), List.of());
    }
}
