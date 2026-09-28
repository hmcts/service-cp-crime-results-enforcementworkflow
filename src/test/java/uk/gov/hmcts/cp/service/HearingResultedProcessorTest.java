package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.EnforcementGatewayClient;
import uk.gov.hmcts.cp.client.GatewayResult;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.mapper.HearingResultedRequestMapper;
import uk.gov.hmcts.cp.mapper.MappingFailureReason;
import uk.gov.hmcts.cp.mapper.MappingResult;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;
import uk.gov.hmcts.cp.support.Fixtures;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HearingResultedProcessorTest {

    private final EnforcementCaseSelector selector = new EnforcementCaseSelector("GAPGD00");
    private final ResultCodeResolver resolver = mock(ResultCodeResolver.class);
    private final NowsDataItemSelector nowsSelector = new NowsDataItemSelector(new uk.gov.hmcts.cp.config.HearingResultProperties());
    private final HearingResultedRequestMapper mapper = mock(HearingResultedRequestMapper.class);
    private final SubmissionStore store = mock(SubmissionStore.class);
    private final EnforcementGatewayClient gateway = mock(EnforcementGatewayClient.class);
    private final HearingResultedProcessor processor = new HearingResultedProcessor(selector, resolver, nowsSelector, mapper, store, gateway);

    private final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");

    @Test
    void skipped_selection_should_not_resolve_or_submit() {
        processor.process(Fixtures.event("hearing-resulted-non-enforcement.json"));

        verifyNoInteractions(resolver, mapper, store, gateway);
    }

    @Test
    void no_gob_code_should_not_map_or_submit() {
        when(resolver.resolve(any(), any())).thenReturn(new ResolvedCodes(List.of("PGPAY"), Set.of(), List.of("PGPAY")));

        processor.process(event);

        verifyNoInteractions(mapper, gateway);
        verify(store, never()).recordSending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mapping_failure_should_not_submit() {
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event);

        verifyNoInteractions(gateway);
        verify(store, never()).recordSending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void success_should_record_sending_then_succeeded() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = new HearingResultedRequest().caseUrn("E012345678");
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), any(), any(), eq("E012345678"), any(), anyString())).thenReturn(Optional.of(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(new HearingResultedResponse().caseUrn("E012345678"), "{}", 200));

        processor.process(event);

        verify(store).recordSucceeded(id, "{}", 200);
    }

    @Test
    void already_submitted_should_not_resolve_or_submit() {
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(
                new ExistingSubmission(UUID.randomUUID(), uk.gov.hmcts.cp.entity.SubmissionStatus.SUCCEEDED, java.time.Instant.now())));

        processor.process(event);

        verifyNoInteractions(resolver, mapper, gateway);
    }

    @Test
    void concurrent_duplicate_should_not_call_the_gateway() {
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(new HearingResultedRequest().caseUrn("E012345678")));
        when(store.recordSending(any(), any(), any(), any(), any(), anyString())).thenReturn(Optional.empty());

        processor.process(event);

        verifyNoInteractions(gateway);
    }

    @Test
    void stale_sending_should_be_marked_interrupted_and_not_resent() {
        final ExistingSubmission stale = new ExistingSubmission(UUID.randomUUID(), uk.gov.hmcts.cp.entity.SubmissionStatus.SENDING,
                java.time.Instant.now().minusSeconds(600));
        when(store.findExisting(any(), any(), any())).thenReturn(Optional.of(stale));
        when(store.isStale(stale)).thenReturn(true);

        processor.process(event);

        verify(store).markInterrupted(stale.id());
        verifyNoInteractions(resolver, mapper, gateway);
    }

    @Test
    void no_gob_code_should_be_recorded_as_skipped() {
        when(resolver.resolve(any(), any())).thenReturn(new ResolvedCodes(List.of("PGPAY"), Set.of(), List.of("PGPAY")));

        processor.process(event);

        verify(store).recordSkippedNoResultCode(any(), any(), any(), eq("E012345678"), any(), eq(List.of("PGPAY")));
    }

    @Test
    void mapping_failure_should_be_recorded() {
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Failed(MappingFailureReason.ADDRESS1_MISSING, "x"));

        processor.process(event);

        verify(store).recordMappingFailed(any(), any(), any(), eq("E012345678"), any(), eq(MappingFailureReason.ADDRESS1_MISSING), eq("x"));
    }

    @Test
    void gateway_failure_should_be_recorded_without_retry() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = new HearingResultedRequest().caseUrn("E012345678");
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), any(), any(), any(), any(), anyString())).thenReturn(Optional.of(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Failure(502, "{}"));

        processor.process(event);

        verify(store).recordFailed(id, 502, "{}");
        verify(gateway, org.mockito.Mockito.times(1)).submit(request);
    }

    @Test
    void reference_data_unavailable_should_be_recorded_as_not_sent() {
        when(resolver.resolve(any(), any())).thenThrow(new uk.gov.hmcts.cp.client.ReferenceDataUnavailableException("down",
                new org.springframework.web.client.ResourceAccessException("timeout")));

        processor.process(event);

        verify(store).recordNotSent(any(), any(), any(), eq("E012345678"), any(), eq("reference data unavailable (ResourceAccessException)"));
        verifyNoInteractions(mapper, gateway);
    }

    @Test
    void accepted_but_unparsable_reply_should_still_be_recorded_as_succeeded() {
        final UUID id = UUID.randomUUID();
        final HearingResultedRequest request = new HearingResultedRequest().caseUrn("E012345678");
        when(resolver.resolve(any(), any())).thenReturn(codes());
        when(mapper.map(any(), any(), any(), any())).thenReturn(new MappingResult.Mapped(request));
        when(store.recordSending(any(), any(), any(), any(), any(), anyString())).thenReturn(Optional.of(id));
        when(gateway.submit(request)).thenReturn(new GatewayResult.Success(null, "not json", 200));

        processor.process(event);

        verify(store).recordSucceeded(id, "not json", 200);
    }

    private static ResolvedCodes codes() {
        return new ResolvedCodes(List.of("SC"), new LinkedHashSet<>(List.of(ResultCodeEnum.SC)), List.of());
    }
}
