package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemName;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemRequest;
import uk.gov.hmcts.cp.openapi.model.NowsDataRequest;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.TestEvents;
import org.springframework.web.client.ResourceAccessException;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

@ExtendWith(OutputCaptureExtension.class)
class HearingResultedProcessorTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-05-03");
    /** Feature 003: the notification id is the submission key (correlation id). */
    private static final UUID NOTIFICATION_ID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000003");
    private static final UUID CASE_ID = UUID.fromString("861aaa70-f142-469d-a7db-9af395347dd5");
    private static final UUID DEFENDANT_ID = UUID.fromString("0ddbc4a7-3c85-4bc3-bdd5-843817320b98");
    /** Distinct from the defendant id: the NOW requests and GOB data belong to the master defendant. */
    private static final UUID MASTER_DEFENDANT_ID = UUID.fromString("5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70");
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
        processor.process(Fixtures.event("hearing-resulted-non-enforcement.json"), NOTIFICATION_ID);

        verifyNoInteractions(resolver, mapper, store, gateway, nowsHandover, reuseHandover);
    }

    // feature 003: a share's submission is keyed by its notification id (in correlation_id, data-model.md)
    @Test
    void share_should_be_keyed_by_the_notification_id() {
        when(resolver.resolve(any(), any())).thenReturn(codes(result(OFFENCE_ID, null)));

        processor.process(event, NOTIFICATION_ID);

        verify(store).findExisting(NOTIFICATION_ID, CASE_ID, DEFENDANT_ID);
    }

    @Test
    void missing_hearing_day_should_be_skipped_without_a_row(final CapturedOutput output) {
        processor.process(new HearingResultedEvent(event.hearing(), false, event.sharedTime(), null), NOTIFICATION_ID);

        verifyNoInteractions(resolver, mapper, store, gateway);
        assertThat(withoutTempDiagnostics(output.getAll())).contains("HEARING_DAY_MISSING");
    }

    @Test
    void missing_shared_time_should_be_skipped_without_a_row(final CapturedOutput output) {
        processor.process(new HearingResultedEvent(event.hearing(), false, null, event.hearingDay()), NOTIFICATION_ID);

        verifyNoInteractions(resolver, mapper, store, gateway);
        assertThat(withoutTempDiagnostics(output.getAll())).contains("SHARED_TIME_MISSING");
    }

    @Test
    void mapping_failure_should_not_submit() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verify(filter).filter(CASE_ID, HEARING_DAY, scCodes());
        verify(store).recordSending(argThat(key -> key.correlationId().equals(NOTIFICATION_ID)),
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

        processor.process(event, NOTIFICATION_ID);

        verify(nowsHandover).handOver(eq(id), argThat(key -> key.hearingDay().equals(HEARING_DAY)
                && key.correlationId().equals(NOTIFICATION_ID)), eq(MASTER_DEFENDANT_ID), eq("1234567890"), eq(false));
    }

    @Test
    void failed_or_unparsable_outcomes_should_not_be_handed_over() {
        final HearingResultedRequest request = request();
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(UUID.randomUUID()));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Failure(502, "{}"), new GatewayResult.Success(null, "not json", 200));

        processor.process(event, NOTIFICATION_ID);
        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(mapper, gateway);
        verify(store).recordSkippedNothingNew(any(), eq("nothing new to send to GOB: offences already sent 1, offences adjourned 0, "
                + "other results already sent 0"));
        verify(reuseHandover).handOver(any(), eq(MASTER_DEFENDANT_ID));
    }

    @Test
    void repeated_nothing_new_delivery_should_not_hand_over_again() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(filter.filter(any(), any(), any())).thenReturn(new OffenceFilterResult(ResolvedCodes.fromResults(List.of(), List.of()), List.of(), 1, 0, 0));
        when(store.recordSkippedNothingNew(any(), anyString())).thenReturn(Optional.empty());

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(reuseHandover);
    }

    // a concurrent share sent one of the offences meanwhile: filter once more, then send what is left
    @Test
    void items_already_sent_should_be_filtered_again_once() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.ItemsAlreadySent());

        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verify(store, times(1)).recordSending(any(), anyString(), any());
        verifyNoInteractions(gateway);
        verify(reuseHandover).handOver(any(), eq(MASTER_DEFENDANT_ID));
    }

    @Test
    void already_submitted_share_should_not_resolve_or_submit() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SUCCEEDED, Instant.now())));

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(resolver, mapper, gateway);
    }

    // a retry after GOB accepted (the earlier run stopped before the hand-over): the stored reply is handed over, not resent
    @Test
    void retry_after_gob_accepted_should_hand_over_the_stored_reply() {
        final UUID rowId = UUID.randomUUID();
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(rowId, SubmissionStatus.SUCCEEDED, Instant.now())));
        when(store.findSucceeded(CASE_ID, DEFENDANT_ID)).thenReturn(List.of(new SucceededSubmission(rowId,
                "{\"defendantDetails\":{\"prosecutorDefendantId\":\"1234567890\"}}", "{\"nowsDataItems\":{\"accountBalance\":1}}")));

        processor.process(event, NOTIFICATION_ID);

        verify(nowsHandover).handOver(eq(rowId), argThat(key -> NOTIFICATION_ID.equals(key.correlationId())), eq(MASTER_DEFENDANT_ID),
                eq("1234567890"), eq(false));
        verifyNoInteractions(resolver, mapper, gateway);
    }

    @Test
    void retry_after_gob_accepted_with_an_unreadable_reply_should_hand_over_nothing() {
        final UUID rowId = UUID.randomUUID();
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(rowId, SubmissionStatus.SUCCEEDED, Instant.now())));
        when(store.findSucceeded(CASE_ID, DEFENDANT_ID)).thenReturn(List.of(new SucceededSubmission(rowId, "{}", "not json")));

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(nowsHandover, gateway);
    }

    @Test
    void retry_after_nothing_new_should_reuse_the_gob_data_again() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SKIPPED_NOTHING_NEW, Instant.now())));

        processor.process(event, NOTIFICATION_ID);

        verify(reuseHandover).handOver(any(), eq(MASTER_DEFENDANT_ID));
        verifyNoInteractions(resolver, mapper, gateway);
    }

    @Test
    void retry_after_no_result_code_should_hand_over_nothing() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SKIPPED_NO_RESULT_CODE, Instant.now())));

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(nowsHandover, reuseHandover, gateway);
    }

    @Test
    void retry_after_a_failed_submission_should_hand_over_nothing() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.FAILED, Instant.now())));

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(nowsHandover, reuseHandover, gateway);
    }

    @Test
    void concurrent_duplicate_should_not_call_the_gateway() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request()));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.AlreadyRecorded());

        processor.process(event, NOTIFICATION_ID);

        verifyNoInteractions(gateway);
    }

    @Test
    void sending_row_of_an_earlier_attempt_should_be_marked_interrupted_and_not_resent() {
        // even a recent one: only one job runs a notification, so its SENDING row was left by an interrupted attempt
        final ExistingSubmission stale = new ExistingSubmission(UUID.randomUUID(), SubmissionStatus.SENDING, Instant.now().minusSeconds(10));
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(stale));

        processor.process(event, NOTIFICATION_ID);

        verify(store).markInterrupted(stale.id());
        verifyNoInteractions(resolver, mapper, gateway);
    }

    // feature 003 FR-010 (replaces feature 002 FR-020): no result with a code: nothing to GOB, nothing to hearing-nows
    @Test
    void no_gob_code_should_be_recorded_as_skipped_and_publish_nothing() {
        final UUID rowId = UUID.randomUUID();
        final ResolvedCodes codes = ResolvedCodes.fromResults(List.of(result(OFFENCE_ID, null)), List.of("PGPAY"));
        when(resolver.resolve(any(), any())).thenReturn(codes);
        when(store.recordSkippedNoResultCode(any(), any())).thenReturn(Optional.of(rowId));

        processor.process(event, NOTIFICATION_ID);

        verify(store).recordSkippedNoResultCode(argThat(key -> "E012345678".equals(key.caseUrn())), eq(List.of("PGPAY")));
        verifyNoInteractions(filter, mapper, gateway);
        verify(store, never()).recordSending(any(), any(), any());
        verifyNoInteractions(reuseHandover, nowsHandover); // feature 003 FR-010: nothing to hearing-nows
    }

    @Test
    void mapping_failure_should_be_recorded() {
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verify(store).recordFailed(id, 502, "{}");
        verify(gateway, times(1)).submit(request);
    }

    @Test
    void reference_data_unavailable_should_be_recorded_as_not_sent() {
        when(resolver.resolve(any(), any())).thenThrow(new ReferenceDataUnavailableException("down", new ResourceAccessException("timeout")));

        processor.process(event, NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verify(store).recordSucceeded(id, "not json", 200);
    }

    // W8: an event without a hearing is skipped (NO_ENFORCEMENT_CASE), not a NullPointerException in the skip log
    @Test
    void event_without_hearing_should_be_skipped_without_error() {
        processor.process(new HearingResultedEvent(null, false, null, null), NOTIFICATION_ID);

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

        processor.process(event, NOTIFICATION_ID);

        verify(store).recordSucceeded(id, "{}", 200);
        assertThat(withoutTempDiagnostics(output.getAll())).contains("GOB response caseUrn does not match the caseUrn sent (E012345678)");
    }

    // QA can see what is about to be sent: ids, caseUrn, result codes and the number of NOWS items, never PII
    @Test
    void sending_should_be_logged_with_codes_and_item_count_only(final CapturedOutput output) {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = new HearingResultedRequest().caseUrn("E012345678")
                .defendantDetails(new DefendantDetails().prosecutorDefendantId("1234567890").forename("Edward"))
                .results(List.of(new HearingResult().resultCode("SC"), new HearingResult().resultCode("DW")))
                .nowsDataRequest(new NowsDataRequest().nowsDataItems(new LinkedHashSet<>(List.of(
                        new NowsDataItemRequest().name(NowsDataItemName.ACCOUNT_BALANCE),
                        new NowsDataItemRequest().name(NowsDataItemName.ACCOUNT_NUMBER)))));
        when(resolver.resolve(any(), any())).thenReturn(scCodes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), anyString(), any())).thenReturn(new SendingRecord.Recorded(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E012345678"), "{}", 200));

        processor.process(event, NOTIFICATION_ID);

        assertThat(withoutTempDiagnostics(output.getAll()))
                .contains("Submission " + id + " caseUrn E012345678: sending to the enforcement gateway (results [SC, DW], NOWS data items 2)")
                .doesNotContain("Edward", "1234567890");
    }

    private static HearingResultedRequest request() {
        return new HearingResultedRequest().caseUrn("E012345678").defendantDetails(new DefendantDetails().prosecutorDefendantId("1234567890"));
    }

    private static ResolvedResult result(final UUID offenceId, final String gobCode) {
        return new ResolvedResult(offenceId, UUID.fromString("aaaaaaa1-0000-0000-0000-000000000001"), TestEvents.SC_TYPE,
                gobCode == null ? "PGPAY" : gobCode, gobCode, false, HEARING_DAY);
    }

    private static ResolvedCodes codes(final ResolvedResult... results) {
        return ResolvedCodes.fromResults(List.of(results), List.of());
    }

    private static ResolvedCodes scCodes() {
        return codes(result(OFFENCE_ID, "SC"));
    }

    private static OffenceFilterResult passThrough(final ResolvedCodes codes) {
        return new OffenceFilterResult(codes, List.of(), 0, 0, 0);
    }

    // feature 002 FR-016, feature 003 FR-008: a reshare is never sent to GOB; the most recent GOB data is handed over again
    @Test
    void reshare_should_reuse_the_gob_data_without_contacting_gob_or_storing_a_row() {
        processor.process(Fixtures.event("hearing-resulted-reshare.json"), NOTIFICATION_ID);

        verify(reuseHandover).handOver(argThat(key -> key.correlationId().equals(NOTIFICATION_ID)), eq(MASTER_DEFENDANT_ID));
        verifyNoInteractions(resolver);
        verifyNoInteractions(mapper, gateway, filter);
        verify(store, never()).recordSending(any(), any(), any());
        verify(store, never()).findExisting(any(), any(), any());
    }

    // feature 003 FR-008/FR-009 (2026-10-22): never sent, even when the first-time result never reached GOB; with no
    // earlier GOB data the notification ends NO_GOB_DATA and the reshare's NOWS stay held
    @Test
    void reshare_without_earlier_gob_data_should_end_with_no_gob_data_and_not_contact_gob() {
        when(reuseHandover.handOver(any(), any())).thenReturn(false);

        processor.process(Fixtures.event("hearing-resulted-reshare.json"), NOTIFICATION_ID);

        verify(nowsHandover).awaitFirstShare(argThat(key -> key.correlationId().equals(NOTIFICATION_ID)));
        verify(nowsHandover).noGobData(argThat(key -> key.correlationId().equals(NOTIFICATION_ID)), eq("RESHARE_NO_GOB_DATA"));
        verifyNoInteractions(mapper, gateway, filter, resolver);
        verify(store, never()).recordSending(any(), any(), any());
    }

    @Test
    void reshare_with_earlier_gob_data_should_not_end_with_no_gob_data() {
        when(reuseHandover.handOver(any(), any())).thenReturn(true);

        processor.process(Fixtures.event("hearing-resulted-reshare.json"), NOTIFICATION_ID);

        verify(nowsHandover, never()).noGobData(any(), any());
    }

    // checked before the reuse, so a first share committing its GOB data meanwhile can't leave the reshare without it
    @Test
    void reshare_while_a_first_share_is_in_progress_should_wait_without_looking_for_gob_data() {
        org.mockito.Mockito.doThrow(new FirstShareInProgressException(NOTIFICATION_ID, UUID.randomUUID()))
                .when(nowsHandover).awaitFirstShare(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> processor.process(Fixtures.event("hearing-resulted-reshare.json"), NOTIFICATION_ID))
                .isInstanceOf(FirstShareInProgressException.class);
        verifyNoInteractions(reuseHandover);
        verify(nowsHandover, never()).noGobData(any(), any());
    }

    // a reshare writes no row and takes the day from the notification: a missing shared time does not block the reuse
    @Test
    void reshare_without_shared_time_should_still_reuse_the_gob_data() {
        final HearingResultedEvent reshare = Fixtures.event("hearing-resulted-reshare.json");
        when(reuseHandover.handOver(any(), any())).thenReturn(true);

        processor.process(new HearingResultedEvent(reshare.hearing(), true, null, reshare.hearingDay()), NOTIFICATION_ID);

        verify(reuseHandover).handOver(argThat(key -> key.correlationId().equals(NOTIFICATION_ID)), eq(MASTER_DEFENDANT_ID));
        verifyNoInteractions(resolver, gateway);
    }
}
