package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Feature 002 FR-016/FR-020, research R16: the most recent GOB data is handed over again; GOB is not contacted. */
class GobDataReuseHandoverTest {

    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID DEFENDANT_ID = UUID.randomUUID();
    private static final UUID MASTER_DEFENDANT_ID = UUID.randomUUID();
    private static final SubmissionKey KEY = new SubmissionKey(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-05-10"),
            CASE_ID, DEFENDANT_ID, "E012345678", Instant.parse("2026-05-10T10:00:00Z"));
    private static final UUID SC_TYPE = UUID.randomUUID();
    private static final UUID SUMM_TYPE = UUID.randomUUID();
    private static final UUID COLLO_TYPE = UUID.randomUUID();

    private final SubmissionStore store = mock(SubmissionStore.class);
    private final NowsHandover nowsHandover = mock(NowsHandover.class);
    private final GobDataReuseHandover reuse = new GobDataReuseHandover(store, nowsHandover);

    @Test
    void most_recent_gob_data_should_be_handed_over_with_the_results_already_sent_as_covered() {
        final UUID older = UUID.randomUUID();
        final UUID newer = UUID.randomUUID();
        final UUID row = UUID.randomUUID();
        when(store.findSucceeded(CASE_ID, DEFENDANT_ID)).thenReturn(List.of(
                new SucceededSubmission(newer, request("26000123A", "SUMM"), "{\"nowsDataItems\":{\"accountBalance\":2}}"),
                new SucceededSubmission(older, request("26000123A", "SC"), "{\"nowsDataItems\":{\"accountBalance\":1}}")));

        reuse.handOver(KEY, MASTER_DEFENDANT_ID, codes(SC_TYPE, ResultCodeEnum.SC, SUMM_TYPE, ResultCodeEnum.SUMM, COLLO_TYPE,
                ResultCodeEnum.COLLO), row, null);

        final ArgumentCaptor<JsonNode> items = ArgumentCaptor.forClass(JsonNode.class);
        verify(nowsHandover).handOver(eq(row), eq(KEY), eq(MASTER_DEFENDANT_ID), eq("26000123A"), items.capture(), eq(true),
                eq(List.of(SC_TYPE, SUMM_TYPE)), eq(null));
        assertThat(items.getValue().toString()).isEqualTo("{\"accountBalance\":2}");
    }

    @Test
    void unparsable_latest_reply_should_fall_back_to_the_previous_accepted_one() {
        final UUID older = UUID.randomUUID();
        when(store.findSucceeded(CASE_ID, DEFENDANT_ID)).thenReturn(List.of(
                new SucceededSubmission(UUID.randomUUID(), request("26000123A", "SUMM"), "\"not json\""),
                new SucceededSubmission(older, request("26000123A", "SC"), "{\"nowsDataItems\":{}}")));

        reuse.handOver(KEY, MASTER_DEFENDANT_ID, codes(SC_TYPE, ResultCodeEnum.SC), null, "reshare");

        verify(nowsHandover).handOver(eq(older), eq(KEY), eq(MASTER_DEFENDANT_ID), eq("26000123A"), any(), anyBoolean(), anyList(),
                eq("reshare"));
    }

    @Test
    void no_accepted_submission_should_hand_over_nothing() {
        when(store.findSucceeded(CASE_ID, DEFENDANT_ID)).thenReturn(List.of());

        reuse.handOver(KEY, MASTER_DEFENDANT_ID, codes(SC_TYPE, ResultCodeEnum.SC), UUID.randomUUID(), null);

        verifyNoInteractions(nowsHandover);
    }

    private static String request(final String accountNumber, final String code) {
        return "{\"defendantDetails\":{\"prosecutorDefendantId\":\"" + accountNumber + "\"},\"results\":[{\"resultCode\":\"" + code + "\"}]}";
    }

    private static ResolvedCodes codes(final Object... typeAndCode) {
        final List<ResolvedResult> results = new ArrayList<>();
        for (int i = 0; i < typeAndCode.length; i += 2) {
            results.add(new ResolvedResult(UUID.randomUUID(), UUID.randomUUID(), (UUID) typeAndCode[i],
                    ((ResultCodeEnum) typeAndCode[i + 1]).getValue(), (ResultCodeEnum) typeAndCode[i + 1], false, LocalDate.parse("2026-05-10")));
        }
        return ResolvedCodes.fromResults(results, List.of());
    }
}
