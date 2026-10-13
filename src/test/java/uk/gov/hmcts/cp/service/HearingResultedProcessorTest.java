package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.client.EnforcementGatewayClient;
import uk.gov.hmcts.cp.client.GatewayResult;
import uk.gov.hmcts.cp.client.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.mapper.HearingResultedRequestMapper;
import uk.gov.hmcts.cp.mapper.MappingFailureReason;
import uk.gov.hmcts.cp.mapper.MappingResult;
import uk.gov.hmcts.cp.openapi.model.DefendantDetails;
import uk.gov.hmcts.cp.openapi.model.HearingResult;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemName;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemRequest;
import uk.gov.hmcts.cp.openapi.model.NowsDataRequest;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.TestEvents;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class HearingResultedProcessorTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-05-03");
    private static final UUID CASE_ID = UUID.fromString("861aaa70-f142-469d-a7db-9af395347dd5");
    private static final UUID DEFENDANT_ID = UUID.fromString("0ddbc4a7-3c85-4bc3-bdd5-843817320b98");
    private static final UUID OFFENCE_ID = UUID.fromString("4fe6e23d-e020-4053-8347-c0aa5a39af97");

    private final EnforcementCaseSelector selector = new EnforcementCaseSelector("GAPGD00");
    private final ResultCodeResolver resolver = mock(ResultCodeResolver.class);
    private final OffenceResultFilter filter = mock(OffenceResultFilter.class);
    private final NowsDataItemSelector nowsSelector = new NowsDataItemSelector(new HearingResultProperties());
    private final HearingResultedRequestMapper mapper = mock(HearingResultedRequestMapper.class);
    private final SubmissionStore store = mock(SubmissionStore.class);
    private final EnforcementGatewayClient gateway = mock(EnforcementGatewayClient.class);
    private final NowsHandover nowsHandover = mock(NowsHandover.class);
    private final GobDataReuseHandover reuseHandover = mock(GobDataReuseHandover.class);
    private final HearingResultedProcessor processor = new HearingResultedProcessor(selector, resolver, filter, nowsSelector, mapper,
            store, gateway, nowsHandover, reuseHandover);

    private final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");

    @BeforeEach
    void passEverythingThroughTheFilter() {
        when(filter.filter(any(), any(), any())).thenAnswer(invocation -> passThrough(invocation.getArgument(2)));
    }

    @Test
    void skipped_selection_should_not_resolve_or_submit() {
        processor.process(Fixtures.event("hearing-resulted-non-enforcement.json"));

        verifyNoInteractions(resolver, mapper, store, gateway, nowsHandover, reuseHandover);
    }

    // feature 002 R18: a share is keyed by hearing id + hearing day
    @Test
    void share_should_be_keyed_by_the_correlation_id_of_hearing_and_hearing_day() {
        when(resolver.resolve(any(), any())).thenReturn(codes(result(OFFENCE_ID, null)));

        processor.process(event);

        verify(store).findExisting(ShareCorrelationId.forShare(HEARING_ID, HEARING_DAY), CASE_ID, DEFENDANT_ID);
    }

    @Test
    void missing_hearing_day_should_be_skipped_without_a_row(final CapturedOutput output) {
        processor.process(new HearingResultedEvent(event.hearing(), false, event.sharedTime(), null));

        verifyNoInteractions(resolver, mapper, store, gateway);
        assertThat(output.getAll()).contains("HEARING_DAY_MISSING");
    }

    @Test
    void missing_shared_time_should_be_skipped_without_a_row(final CapturedOutput output) {
        processor.process(new HearingResultedEvent(event.hearing(), false, null, event.hearingDay()));

        verifyNoInteractions(resolver, mapper, store, gateway);
        assertThat(output.getAll()).contains("SHARED_TIME_MISSING");
    }

    @Test
    void no_gob_code_should_not_map_or_submit() {
        when(resolver.resolve(any(), any())).thenReturn(codes(result(OFFENCE_ID, null)));

        processor.process(event);

        verifyNoInteractions(mapper, gateway);
        verify(store, never()).recordSending(any(), any(), any());
    }

    @Test
    void mapping_failure_should_not_submit() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event);

        verifyNoInteractions(gateway);
        verify(store, never()).recordSending(any(), any(), any());
    }

    @Test
    void success_should_record_sending_then_succeeded() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E012345678"), "{}", 200));

        processor.process(event);

        verify(store).recordSucceeded(id, "{}", 200);
    }

    // feature 002 FR-018: the items sent are recorded with the SENDING row
    @Test
    void sending_should_record_the_items_the_filter_selected() {
        final List<SentItem> items = List.of(SentItem.offence(OFFENCE_ID));
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(filter.filter(any(), any(), any())).thenAnswer(invocation ->
                new OffenceFilterResult(invocation.getArgument(2), items, 0, 0, 0));
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.AlreadyRecorded());

        processor.process(event);

        verify(filter).filter(CASE_ID, HEARING_DAY, scCodes());
        verify(store).recordSending(argThat(key -> key.correlationId().equals(ShareCorrelationId.forShare(HEARING_ID, HEARING_DAY))),
                anyString(), eq(items));
    }

    // US1: GOB accepted with a parsable reply → GOB's data handed over to hearing-nows, with the results sent
    @Test
    void accepted_reply_should_be_handed_over_to_hearing_nows() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E012345678"),
                "{\"caseUrn\":\"E012345678\",\"nowsDataItems\":{\"accountBalance\":125.5}}", 200));

        processor.process(event);

        final ArgumentCaptor<JsonNode> items = ArgumentCaptor.forClass(JsonNode.class);
        verify(nowsHandover).handOver(eq(id), argThat(key -> key.hearingDay().equals(HEARING_DAY)), eq(DEFENDANT_ID),
                eq("1234567890"), items.capture(), eq(false), eq(List.of(TestEvents.SC_TYPE)), isNull());
        assertThat(items.getValue().toString()).isEqualTo("{\"accountBalance\":125.5}");
    }

    @Test
    void failed_or_unparsable_outcomes_should_not_be_handed_over() {
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(UUID.randomUUID()));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Failure(502, "{}"), new GatewayResult.Success(null, "not json", 200));

        processor.process(event);
        processor.process(event);

        verifyNoInteractions(nowsHandover, reuseHandover);
    }

    // feature 002 FR-020: nothing left to send → no GOB call, the most recent GOB data is reused
    @Test
    void nothing_new_should_not_call_gob_and_should_reuse_the_latest_gob_data() {
        final UUID rowId = UUID.randomUUID();
        final ResolvedCodes codes = scCodes();
        when(resolver.resolve(any(), any())).thenReturn(codes);
        when(filter.filter(any(), any(), any())).thenReturn(new OffenceFilterResult(ResolvedCodes.fromResults(List.of(), List.of()), List.of(), 1, 0, 0));
        when(store.recordSkippedNothingNew(any(), anyString())).thenReturn(Optional.of(rowId));

        processor.process(event);

        verifyNoInteractions(mapper, gateway);
        verify(store).recordSkippedNothingNew(any(), eq("nothing new to send to GOB: offences already sent 1, offences adjourned 0, "
                + "other results already sent 0"));
        verify(reuseHandover).handOver(any(), eq(DEFENDANT_ID), eq(codes), eq(rowId), isNull());
    }

    @Test
    void repeated_nothing_new_delivery_should_not_hand_over_again() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(filter.filter(any(), any(), any())).thenReturn(new OffenceFilterResult(ResolvedCodes.fromResults(List.of(), List.of()), List.of(), 1, 0, 0));
        when(store.recordSkippedNothingNew(any(), anyString())).thenReturn(Optional.empty());

        processor.process(event);

        verifyNoInteractions(reuseHandover);
    }

    // a concurrent share sent one of the offences meanwhile: filter once more, then send what is left
    @Test
    void items_already_sent_should_be_filtered_again_once() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.ItemsAlreadySent());

        processor.process(event);

        verify(filter, times(2)).filter(any(), any(), any());
        verify(store, times(2)).recordSending(any(), anyString(), any());
        verifyNoInteractions(gateway);
    }

    // a concurrent share sent every offence meanwhile: the second filter leaves nothing, so it is recorded as nothing new (FR-020)
    @Test
    void items_all_sent_by_a_concurrent_share_should_be_recorded_as_nothing_new() {
        final UUID rowId = UUID.randomUUID();
        final ResolvedCodes codes = scCodes();
        when(resolver.resolve(any(), any())).thenReturn(codes);
        when(filter.filter(any(), any(), any())).thenAnswer(invocation -> passThrough(invocation.getArgument(2)))
                .thenReturn(new OffenceFilterResult(ResolvedCodes.fromResults(List.of(), List.of()), List.of(), 1, 0, 0));
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.ItemsAlreadySent());
        when(store.recordSkippedNothingNew(any(), anyString())).thenReturn(Optional.of(rowId));

        processor.process(event);

        verify(store, times(1)).recordSending(any(), anyString(), any());
        verifyNoInteractions(gateway);
        verify(reuseHandover).handOver(any(), eq(DEFENDANT_ID), eq(codes), eq(rowId), isNull());
    }

    @Test
    void already_submitted_share_should_not_resolve_or_submit() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SUCCEEDED, Instant.now())));

        processor.process(event);

        verifyNoInteractions(resolver, mapper, gateway);
    }

    @Test
    void concurrent_duplicate_should_not_call_the_gateway() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.AlreadyRecorded());

        processor.process(event);

        verifyNoInteractions(gateway);
    }

    @Test
    void stale_sending_should_be_marked_interrupted_and_not_resent() {
        final ExistingSubmission stale = new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SENDING, Instant.now().minusSeconds(600));
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(stale));
        when(store.isStale(stale)).thenReturn(true);

        processor.process(event);

        verify(store).markInterrupted(stale.id());
        verifyNoInteractions(resolver, mapper, gateway);
    }

    // feature 002 FR-020: no result to send, so the most recent GOB data (if any) is reused for NOWS of a later share
    @Test
    void no_gob_code_should_be_recorded_as_skipped_and_reuse_the_latest_gob_data() {
        final UUID rowId = UUID.randomUUID();
        final ResolvedCodes codes = ResolvedCodes.fromResults(List.of(result(OFFENCE_ID, null)), List.of("PGPAY"));
        when(resolver.resolve(any(), any())).thenReturn(codes);
        when(store.recordSkippedNoResultCode(any(), any())).thenReturn(Optional.of(rowId));

        processor.process(event);

        verify(store).recordSkippedNoResultCode(argThat(key -> "E012345678".equals(key.caseUrn())), eq(List.of("PGPAY")));
        verifyNoInteractions(filter, mapper, gateway);
        verify(reuseHandover).handOver(any(), eq(DEFENDANT_ID), eq(codes), eq(rowId), isNull());
    }

    @Test
    void repeated_no_gob_code_delivery_should_not_hand_over_again() {
        when(resolver.resolve(any(), any())).thenReturn(ResolvedCodes.fromResults(List.of(result(OFFENCE_ID, null)), List.of("PGPAY")));
        when(store.recordSkippedNoResultCode(any(), any())).thenReturn(Optional.empty());

        processor.process(event);

        verifyNoInteractions(reuseHandover);
    }

    @Test
    void mapping_failure_should_be_recorded() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event);

        verify(store).recordMappingFailed(argThat(key -> "E012345678".equals(key.caseUrn())), eq(MappingFailureReason.ADDRESS1_MISSING), eq("x"));
    }

    @Test
    void gateway_failure_should_be_recorded_without_retry() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Failure(502, "{}"));

        processor.process(event);

        verify(store).recordFailed(id, 502, "{}");
        verify(gateway, times(1)).submit(request);
    }

    @Test
    void reference_data_unavailable_should_be_recorded_as_not_sent() {
        when(resolver.resolve(any(), any())).thenThrow(new ReferenceDataUnavailableException("down", new ResourceAccessException("timeout")));

        processor.process(event);

        verify(store).recordNotSent(argThat(key -> "E012345678".equals(key.caseUrn())), eq("reference data unavailable (ResourceAccessException)"));
        verifyNoInteractions(mapper, gateway);
    }

    @Test
    void accepted_but_unparsable_reply_should_still_be_recorded_as_succeeded() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(null, "not json", 200));

        processor.process(event);

        verify(store).recordSucceeded(id, "not json", 200);
    }

    // W8: an event without a hearing is skipped (NO_ENFORCEMENT_CASE), not a NullPointerException in the skip log
    @Test
    void event_without_hearing_should_be_skipped_without_error() {
        processor.process(new HearingResultedEvent(null, false, null, null));

        verifyNoInteractions(resolver, mapper, store, gateway);
    }

    // gateway contract: WARN when GOB's reply names a different caseUrn; the submission still succeeded
    @Test
    void reply_for_another_case_urn_should_warn_and_still_succeed(final CapturedOutput output) {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E099999999"), "{}", 200));

        processor.process(event);

        verify(store).recordSucceeded(id, "{}", 200);
        assertThat(output.getAll()).contains("GOB response caseUrn does not match the caseUrn sent (E012345678)");
    }

    // QA can see what is about to be sent: ids, caseUrn, result codes and the number of NOWS items, never PII
    @Test
    void sending_should_be_logged_with_codes_and_item_count_only(final CapturedOutput output) {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = new HearingResultedRequest().caseUrn("E012345678")
                .defendantDetails(new DefendantDetails().prosecutorDefendantId("1234567890").forename("Edward"))
                .results(List.of(new HearingResult().resultCode(ResultCodeEnum.SC), new HearingResult().resultCode(ResultCodeEnum.DW)))
                .nowsDataRequest(new NowsDataRequest().nowsDataItems(new LinkedHashSet<>(List.of(
                        new NowsDataItemRequest().name(NowsDataItemName.ACCOUNT_BALANCE),
                        new NowsDataItemRequest().name(NowsDataItemName.ACCOUNT_NUMBER)))));
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E012345678"), "{}", 200));

        processor.process(event);

        assertThat(output.getAll())
                .contains("Submission " + id + " caseUrn E012345678: sending to the enforcement gateway (results [SC, DW], NOWS data items 2)")
                .doesNotContain("Edward", "1234567890");
    }

    private static HearingResultedRequest request() {
        return new HearingResultedRequest().caseUrn("E012345678").defendantDetails(new DefendantDetails().prosecutorDefendantId("1234567890"));
    }

    private static ResolvedResult result(final UUID offenceId, final ResultCodeEnum gobCode) {
        return new ResolvedResult(offenceId, UUID.fromString("aaaaaaa1-0000-0000-0000-000000000001"), TestEvents.SC_TYPE,
                gobCode == null ? "PGPAY" : gobCode.getValue(), gobCode, false, HEARING_DAY);
    }

    private static ResolvedCodes codes(final ResolvedResult... results) {
        return ResolvedCodes.fromResults(List.of(results), List.of());
    }

    private static ResolvedCodes scCodes() {
        return codes(result(OFFENCE_ID, ResultCodeEnum.SC));
    }

    private static OffenceFilterResult passThrough(final ResolvedCodes codes) {
        return new OffenceFilterResult(codes, List.of(), 0, 0, 0);
    }

    // feature 002 FR-016 (T069): a reshare is never sent to GOB; the most recent GOB data is handed over again
    @Test
    void reshare_should_reuse_the_gob_data_without_contacting_gob_or_storing_a_row() {
        final ResolvedCodes codes = scCodes();
        when(resolver.resolve(any(), any())).thenReturn(codes);

        processor.process(Fixtures.event("hearing-resulted-reshare.json"));

        verify(reuseHandover).handOver(argThat(key -> key.correlationId().equals(ShareCorrelationId.forShare(HEARING_ID, HEARING_DAY))),
                eq(DEFENDANT_ID), eq(codes), isNull(), eq("reshare"));
        verifyNoInteractions(mapper, gateway, filter);
        verify(store, never()).recordSending(any(), any(), any());
        verify(store, never()).findExisting(any(), any(), any());
    }

    @Test
    void reshare_with_reference_data_unavailable_should_hand_over_nothing() {
        when(resolver.resolve(any(), any())).thenThrow(new ReferenceDataUnavailableException("down", new ResourceAccessException("timeout")));

        processor.process(Fixtures.event("hearing-resulted-reshare.json"));

        verifyNoInteractions(reuseHandover, gateway);
    }

    @Test
    void reshare_without_shared_time_should_hand_over_nothing(final CapturedOutput output) {
        final HearingResultedEvent reshare = Fixtures.event("hearing-resulted-reshare.json");

        processor.process(new HearingResultedEvent(reshare.hearing(), true, null, reshare.hearingDay()));

        verifyNoInteractions(resolver, reuseHandover, gateway);
        assertThat(output.getAll()).contains("SHARED_TIME_MISSING");
    }

    @Test
    void reshare_without_hearing_day_should_hand_over_nothing(final CapturedOutput output) {
        final HearingResultedEvent reshare = Fixtures.event("hearing-resulted-reshare.json");

        processor.process(new HearingResultedEvent(reshare.hearing(), true, reshare.sharedTime(), null));

        verifyNoInteractions(resolver, reuseHandover, gateway);
        assertThat(output.getAll()).contains("HEARING_DAY_MISSING");
    }
}
