package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.repository.EnforcementResultNotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 005 (data-model.md): every write commits on its own (REQUIRES_NEW), mirroring NotificationStore. */
@ExtendWith(OutputCaptureExtension.class)
class ResultNotificationStoreTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID TRIGGER_ID = UUID.randomUUID();
    private static final UUID HEARING_ID = UUID.randomUUID();
    private static final LocalDate HEARING_DATE = LocalDate.parse("2026-11-24");
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final EnforcementResultNotificationRepository repository = mock(EnforcementResultNotificationRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ResultNotificationStore store;

    ResultNotificationStoreTest() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store = new ResultNotificationStore(repository, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void find_should_delegate_to_the_repository() {
        final EnforcementResultNotificationEntity entity = entity();
        when(repository.findById(ID)).thenReturn(Optional.of(entity));

        assertThat(store.find(ID)).contains(entity);
    }

    @Test
    void previous_share_snapshot_should_delegate_to_the_repository() {
        final EnforcementResultNotificationEntity previous = entity();
        when(repository.findPreviousShareSnapshot(HEARING_ID, "REF1")).thenReturn(Optional.of(previous));

        assertThat(store.previousShareSnapshot(HEARING_ID, "REF1")).contains(previous);
    }

    @Test
    void create_requested_should_build_a_row_with_the_requested_status() {
        final Optional<EnforcementResultNotificationEntity> created = store.createRequested(TRIGGER_ID, HEARING_ID,
                HEARING_DATE, "REF1", NotificationType.FIRST_SHARE, "URN1", "010", Area.ENFORCEMENT);

        assertThat(created).isPresent();
        final EnforcementResultNotificationEntity entity = created.orElseThrow();
        assertThat(entity.getTriggerNotificationId()).isEqualTo(TRIGGER_ID);
        assertThat(entity.getHearingId()).isEqualTo(HEARING_ID);
        assertThat(entity.getHearingDate()).isEqualTo(HEARING_DATE);
        assertThat(entity.getRelatedReferenceNumber()).isEqualTo("REF1");
        assertThat(entity.getNotificationType()).isEqualTo(NotificationType.FIRST_SHARE);
        assertThat(entity.getCaseUrn()).isEqualTo("URN1");
        assertThat(entity.getDivisionCode()).isEqualTo("010");
        assertThat(entity.getArea()).isEqualTo(Area.ENFORCEMENT);
        assertThat(entity.getStatus()).isEqualTo(ResultNotificationStatus.REQUESTED);
        assertThat(entity.getId()).isNotNull();
    }

    // FR-015 / research.md R10: a duplicate/retried fan-out of the same trigger call is not a new row
    @Test
    void create_requested_should_return_empty_on_a_duplicate_trigger_and_reference() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(store.createRequested(TRIGGER_ID, HEARING_ID, HEARING_DATE, "REF1", NotificationType.FIRST_SHARE, "URN1",
                "010", Area.ENFORCEMENT)).isEmpty();
    }

    // constitution IV: relatedReferenceNumber is this row's GOB account number - never logged on the duplicate path
    @Test
    void create_requested_duplicate_log_line_should_never_contain_the_reference_number(
            final org.springframework.boot.test.system.CapturedOutput output) {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        store.createRequested(TRIGGER_ID, HEARING_ID, HEARING_DATE, "9999999999", NotificationType.FIRST_SHARE, "URN1",
                "010", Area.ENFORCEMENT);

        assertThat(output.getAll()).doesNotContain("9999999999");
    }

    @Test
    void mark_suppressed_should_update_the_status() {
        givenStoredEntity();

        store.markSuppressed(ID);

        assertThat(saved().getStatus()).isEqualTo(ResultNotificationStatus.SUPPRESSED);
    }

    @Test
    void mark_inbox_not_found_should_update_status_division_code_and_area() {
        givenStoredEntity();

        store.markInboxNotFound(ID, "010", Area.ENFORCEMENT);

        final EnforcementResultNotificationEntity entity = saved();
        assertThat(entity.getStatus()).isEqualTo(ResultNotificationStatus.INBOX_NOT_FOUND);
        assertThat(entity.getDivisionCode()).isEqualTo("010");
        assertThat(entity.getArea()).isEqualTo(Area.ENFORCEMENT);
    }

    @Test
    void mark_doc_requested_should_set_every_resolved_field() {
        givenStoredEntity();

        store.markDocRequested(ID, "010", Area.ENFORCEMENT, "team@example.gov.uk", "[]", "payload-uri", "destination-uri");

        final EnforcementResultNotificationEntity entity = saved();
        assertThat(entity.getStatus()).isEqualTo(ResultNotificationStatus.DOC_REQUESTED);
        assertThat(entity.getDivisionCode()).isEqualTo("010");
        assertThat(entity.getArea()).isEqualTo(Area.ENFORCEMENT);
        assertThat(entity.getTeamEmail()).isEqualTo("team@example.gov.uk");
        assertThat(entity.getResultShortCodes()).isEqualTo("[]");
        assertThat(entity.getPayloadFileUri()).isEqualTo("payload-uri");
        assertThat(entity.getDestinationFileUri()).isEqualTo("destination-uri");
    }

    @Test
    void mark_doc_ready_should_update_the_status() {
        givenStoredEntity();

        store.markDocReady(ID);

        assertThat(saved().getStatus()).isEqualTo(ResultNotificationStatus.DOC_READY);
    }

    @Test
    void mark_doc_failed_should_set_status_and_error() {
        givenStoredEntity();

        store.markDocFailed(ID, "DocGenerationUnavailableException");

        final EnforcementResultNotificationEntity entity = saved();
        assertThat(entity.getStatus()).isEqualTo(ResultNotificationStatus.DOC_FAILED);
        assertThat(entity.getLastError()).isEqualTo("DocGenerationUnavailableException");
    }

    @Test
    void mark_email_requested_should_update_the_status() {
        givenStoredEntity();

        store.markEmailRequested(ID);

        assertThat(saved().getStatus()).isEqualTo(ResultNotificationStatus.EMAIL_REQUESTED);
    }

    @Test
    void mark_sent_should_update_the_status() {
        givenStoredEntity();

        store.markSent(ID);

        assertThat(saved().getStatus()).isEqualTo(ResultNotificationStatus.SENT);
    }

    @Test
    void mark_email_failed_should_set_status_and_error() {
        givenStoredEntity();

        store.markEmailFailed(ID, "GATEWAY_REJECTED");

        final EnforcementResultNotificationEntity entity = saved();
        assertThat(entity.getStatus()).isEqualTo(ResultNotificationStatus.EMAIL_FAILED);
        assertThat(entity.getLastError()).isEqualTo("GATEWAY_REJECTED");
    }

    // FR-018: purged_at is only ever set explicitly, after notification-sent confirms delivery
    @Test
    void mark_purged_should_set_purged_at_never_before_called() {
        givenStoredEntity();

        store.markPurged(ID);

        assertThat(saved().getPurgedAt()).isEqualTo(NOW);
    }

    @Test
    void null_error_should_be_kept_as_null() {
        givenStoredEntity();

        store.markDocFailed(ID, null);

        assertThat(saved().getLastError()).isNull();
    }

    @Test
    void long_error_should_be_truncated_to_255_characters() {
        givenStoredEntity();
        final String tooLong = "x".repeat(300);

        store.markDocFailed(ID, tooLong);

        assertThat(saved().getLastError()).hasSize(255);
    }

    private void givenStoredEntity() {
        when(repository.findById(ID)).thenReturn(Optional.of(entity()));
    }

    private EnforcementResultNotificationEntity saved() {
        final org.mockito.ArgumentCaptor<EnforcementResultNotificationEntity> captor =
                org.mockito.ArgumentCaptor.forClass(EnforcementResultNotificationEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static EnforcementResultNotificationEntity entity() {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(ID);
        entity.setStatus(ResultNotificationStatus.REQUESTED);
        return entity;
    }
}
