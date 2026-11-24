package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.client.DocGenerationUnavailableException;
import uk.gov.hmcts.cp.client.DocGeneratorClient;
import uk.gov.hmcts.cp.client.EnforcementAreaClient;
import uk.gov.hmcts.cp.client.EnforcementAreaUnavailableException;
import uk.gov.hmcts.cp.client.GenerateDocumentCommand;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.client.TeamInbox;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (FR-011, FR-003, FR-004, Clarification 2026-11-24): the shared gate/inbox/dispatch pipeline, identical
 * for FIRST_SHARE and AMEND_RESHARE.
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchResultNotificationTaskTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID TRIGGER_ID = UUID.randomUUID();
    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");
    // Division Code 010 -> Enforcement (spec.md's own worked example)
    private static final String REFERENCE = "0100104567C";

    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final NotificationStore triggerStore = mock(NotificationStore.class);
    private final HearingResultsSource resultsSource = mock(HearingResultsSource.class);
    private final EnforcementCaseSelector caseSelector = mock(EnforcementCaseSelector.class);
    private final ResultCodeResolver resultCodeResolver = mock(ResultCodeResolver.class);
    private final TriggerShortCodeMatcher triggerShortCodeMatcher = mock(TriggerShortCodeMatcher.class);
    private final EnforcementAreaClient enforcementAreaClient = mock(EnforcementAreaClient.class);
    private final DocGeneratorClient docGeneratorClient = mock(DocGeneratorClient.class);
    private final ResultNotificationBlobClient blobClient = mock(ResultNotificationBlobClient.class);
    private final ResultNotificationPayloadBuilder payloadBuilder = mock(ResultNotificationPayloadBuilder.class);
    private final ResultNotificationProperties properties = new ResultNotificationProperties();
    private final ResultNotificationRetries retries = new ResultNotificationRetries(properties);
    private final MatchResultNotificationTask task = new MatchResultNotificationTask(store, triggerStore, resultsSource,
            caseSelector, resultCodeResolver, triggerShortCodeMatcher, enforcementAreaClient, docGeneratorClient, blobClient,
            payloadBuilder, properties, retries);

    @Test
    void row_not_requested_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.of(row(NotificationType.FIRST_SHARE, ResultNotificationStatus.SUPPRESSED)));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(enforcementAreaClient, never()).findTeamInbox(any(Integer.class));
    }

    @Test
    void missing_row_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void results_not_ready_should_be_retried_while_attempts_remain() {
        when(store.find(ID)).thenReturn(Optional.of(row(NotificationType.FIRST_SHARE, ResultNotificationStatus.REQUESTED)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.empty());

        assertThat(task.execute(info(2)).isShouldRetry()).isTrue();
    }

    @Test
    void hearing_no_longer_in_scope_should_complete_without_dispatching() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        when(store.find(ID)).thenReturn(Optional.of(row(NotificationType.FIRST_SHARE, ResultNotificationStatus.REQUESTED)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, false)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(new SelectionResult.Skipped(SkipReason.NO_ENFORCEMENT_CASE));

        task.execute(info(3));

        verify(store, never()).markSuppressed(any());
        verify(enforcementAreaClient, never()).findTeamInbox(any(Integer.class));
    }

    @Test
    void no_trigger_short_code_should_suppress_and_log_the_full_current_state() {
        givenSelectedDefendant(false);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(false);

        task.execute(info(3));

        verify(store).markSuppressed(ID);
        verify(enforcementAreaClient, never()).findTeamInbox(any(Integer.class));
    }

    @Test
    void inbox_not_found_should_mark_the_row_and_never_dispatch() {
        givenSelectedDefendant(false);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.empty());

        task.execute(info(3));

        verify(store).markInboxNotFound(ID, "010", Area.ENFORCEMENT);
        verify(blobClient, never()).uploadPayload(any(), any(), any());
    }

    // a transient lookup failure must never be collapsed into INBOX_NOT_FOUND (FR-006)
    @Test
    void transient_inbox_lookup_failure_should_be_retried_not_marked_not_found() {
        givenSelectedDefendant(false);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenThrow(new EnforcementAreaUnavailableException("HTTP 503", null));

        final ExecutionInfo next = task.execute(info(2));

        assertThat(next.isShouldRetry()).isTrue();
        verify(store, never()).markInboxNotFound(any(), any(), any());
    }

    @Test
    void first_share_should_build_current_state_payload_upload_and_send_generate_document() {
        givenSelectedDefendant(false);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        when(payloadBuilder.buildResultShortCodes(any(), any(), eq(Area.ENFORCEMENT))).thenReturn("[]");
        when(payloadBuilder.buildDocGenPayload(eq(NotificationType.FIRST_SHARE), eq(HEARING_ID), eq(DAY), any(), eq(REFERENCE), eq("[]")))
                .thenReturn("{\"payload\":true}");
        when(blobClient.uploadPayload(ID, NotificationType.FIRST_SHARE, "{\"payload\":true}")).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");

        task.execute(info(3));

        verify(store).markDocRequested(ID, "010", Area.ENFORCEMENT, "team@example.gov.uk", "[]", "payload-url", "destination-url");
        verify(store, never()).previousShareSnapshot(any(), any());
        final org.mockito.ArgumentCaptor<GenerateDocumentCommand> captor =
                org.mockito.ArgumentCaptor.forClass(GenerateDocumentCommand.class);
        verify(docGeneratorClient).generateDocument(captor.capture());
        assertThat(captor.getValue().sourceCorrelationId()).isEqualTo(ID);
        assertThat(captor.getValue().payloadFileUri()).isEqualTo("payload-url");
        assertThat(captor.getValue().destinationFileUri()).isEqualTo("destination-url");
        assertThat(captor.getValue().conversionFormat()).isEqualTo("pdf");
    }

    @Test
    void amend_reshare_should_read_the_previous_share_snapshot_once_the_gate_passes() {
        givenSelectedDefendant(true);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        when(payloadBuilder.buildResultShortCodes(any(), any(), eq(Area.ENFORCEMENT))).thenReturn("[]");
        final EnforcementResultNotificationEntity previous = row(NotificationType.FIRST_SHARE, ResultNotificationStatus.SENT);
        previous.setResultShortCodes("[\"previous\"]");
        when(store.previousShareSnapshot(HEARING_ID, REFERENCE)).thenReturn(Optional.of(previous));
        when(payloadBuilder.buildDocGenPayload(eq(NotificationType.AMEND_RESHARE), eq(HEARING_ID), eq(DAY), any(), eq(REFERENCE),
                eq("[]"), eq("[\"previous\"]"), any())).thenReturn("{}");
        when(blobClient.uploadPayload(any(), any(), any())).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");

        task.execute(info(3));

        verify(store).previousShareSnapshot(HEARING_ID, REFERENCE);
        verify(docGeneratorClient).generateDocument(any());
    }

    // FR-012: a reshare with no observable change from the previous share still produces an email once the FR-011
    // gate is met - there is no separate content-diff suppression check anywhere in this task
    @Test
    void amend_reshare_with_no_observable_change_should_still_dispatch_not_suppress() {
        givenSelectedDefendant(true);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        final String identicalShortCodes = "[\"same\"]";
        when(payloadBuilder.buildResultShortCodes(any(), any(), eq(Area.ENFORCEMENT))).thenReturn(identicalShortCodes);
        final EnforcementResultNotificationEntity previous = row(NotificationType.FIRST_SHARE, ResultNotificationStatus.SENT);
        previous.setResultShortCodes(identicalShortCodes); // content-identical to the current state
        when(store.previousShareSnapshot(HEARING_ID, REFERENCE)).thenReturn(Optional.of(previous));
        when(payloadBuilder.buildDocGenPayload(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("{}");
        when(blobClient.uploadPayload(any(), any(), any())).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");

        task.execute(info(3));

        verify(store, never()).markSuppressed(any());
        verify(store).markDocRequested(any(), any(), any(), any(), any(), any(), any());
        verify(docGeneratorClient).generateDocument(any());
    }

    // research.md R9: the amend-reshare path still proceeds when no previous SENT row exists at all
    @Test
    void amend_reshare_with_no_previous_row_should_still_dispatch() {
        givenSelectedDefendant(true);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        when(payloadBuilder.buildResultShortCodes(any(), any(), eq(Area.ENFORCEMENT))).thenReturn("[]");
        when(store.previousShareSnapshot(HEARING_ID, REFERENCE)).thenReturn(Optional.empty());
        when(payloadBuilder.buildDocGenPayload(eq(NotificationType.AMEND_RESHARE), eq(HEARING_ID), eq(DAY), any(), eq(REFERENCE),
                eq("[]"), eq(null), any())).thenReturn("{}");
        when(blobClient.uploadPayload(any(), any(), any())).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");

        task.execute(info(3));

        verify(docGeneratorClient).generateDocument(any());
    }

    @Test
    void doc_generation_failure_should_be_retried_then_marked_doc_failed_on_the_last_attempt() {
        givenSelectedDefendant(false);
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        when(payloadBuilder.buildResultShortCodes(any(), any(), eq(Area.ENFORCEMENT))).thenReturn("[]");
        when(payloadBuilder.buildDocGenPayload(any(), any(), any(), any(), any(), any())).thenReturn("{}");
        when(blobClient.uploadPayload(any(), any(), any())).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");
        org.mockito.Mockito.doThrow(new DocGenerationUnavailableException("HTTP 503", null)).when(docGeneratorClient)
                .generateDocument(any());

        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markDocFailed(ID, "DocGenerationUnavailableException");
    }

    // constitution Principle IV: no defendant name/DOB/NINO/address/bank detail ever reaches a log line this feature
    // adds - the real fixture's defendant carries all of these; the real ResultNotificationPayloadBuilder (used
    // here, not mocked) only ever reads offence/judicial-result fields, never personDefendant()/legalEntityDefendant()
    @Test
    void no_defendant_pii_should_reach_the_doc_failed_log_line_even_with_real_offence_content(final CapturedOutput output) {
        final MatchResultNotificationTask realPayloadBuilderTask = new MatchResultNotificationTask(store, triggerStore,
                resultsSource, caseSelector, resultCodeResolver, triggerShortCodeMatcher, enforcementAreaClient, docGeneratorClient,
                blobClient, new ResultNotificationPayloadBuilder(properties, JsonMapper.builder().build()), properties, retries);
        givenSelectedDefendant(false);
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();
        final HearingResultedEvent.Offence offence = defendant.offences().getFirst();
        final HearingResultedEvent.JudicialResult result = offence.judicialResults().getFirst();
        when(resultCodeResolver.resolve(event, defendant)).thenReturn(ResolvedCodes.fromResults(java.util.List.of(
                new ResolvedResult(offence.id(), result.judicialResultId(), result.judicialResultTypeId(), "CW", "CW", false,
                        result.orderedDate())), java.util.List.of()));
        when(triggerShortCodeMatcher.matches(any(), eq(Area.ENFORCEMENT))).thenReturn(true);
        when(enforcementAreaClient.findTeamInbox(10)).thenReturn(Optional.of(new TeamInbox("team@example.gov.uk")));
        when(blobClient.uploadPayload(any(), any(), any())).thenReturn("payload-url");
        when(blobClient.destinationUrl(ID)).thenReturn("destination-url");
        org.mockito.Mockito.doThrow(new DocGenerationUnavailableException("HTTP 503", null)).when(docGeneratorClient)
                .generateDocument(any());

        realPayloadBuilderTask.execute(info(1));

        // the real ResultNotificationPayloadBuilder ran (building result_short_codes from the fixture's real offence
        // and judicial result) yet no defendant identity field from that same fixture ever reaches the output
        assertThat(output.getAll()).doesNotContain("Edward", "Harrison", "2002-01-10", "QQ123456C", "1 High Street");
    }

    private void givenSelectedDefendant(final boolean reshare) {
        final HearingResultedEvent event = Fixtures.event(reshare ? "hearing-resulted-reshare.json" : "hearing-resulted-enforcement.json");
        final HearingResultedEvent.Defendant defendant = event.hearing().prosecutionCases().getFirst().defendants().getFirst();
        when(store.find(ID)).thenReturn(Optional.of(
                row(reshare ? NotificationType.AMEND_RESHARE : NotificationType.FIRST_SHARE, ResultNotificationStatus.REQUESTED)));
        when(resultsSource.sharedResults(HEARING_ID, DAY, reshare)).thenReturn(Optional.of(event));
        when(caseSelector.select(event)).thenReturn(reshare
                ? new SelectionResult.Reshare(event.hearing().prosecutionCases().getFirst(), defendant)
                : new SelectionResult.Selected(event.hearing().prosecutionCases().getFirst(), defendant));
        when(resultCodeResolver.resolve(event, defendant)).thenReturn(ResolvedCodes.fromResults(java.util.List.of(), java.util.List.of()));
    }

    private static EnforcementResultNotificationEntity row(final NotificationType type, final ResultNotificationStatus status) {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(ID);
        entity.setTriggerNotificationId(TRIGGER_ID);
        entity.setHearingId(HEARING_ID);
        entity.setHearingDate(DAY);
        entity.setRelatedReferenceNumber(REFERENCE);
        entity.setNotificationType(type);
        entity.setStatus(status);
        entity.setCaseUrn("URN123");
        entity.setReceivedAt(Instant.parse("2026-05-03T10:00:00Z"));
        return entity;
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jakarta.json.Json.createObjectBuilder().add("resultNotificationId", ID.toString()).build())
                .withAssignedTaskName(ResultNotificationTasks.MATCH_RESULT_NOTIFICATION).withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED).withRetryAttemptsRemaining(retriesRemaining).build();
    }

    private static <T> T eq(final T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
