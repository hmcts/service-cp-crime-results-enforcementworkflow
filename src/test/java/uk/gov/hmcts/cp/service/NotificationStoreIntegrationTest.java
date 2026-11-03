package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.FailedStage;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.integration.WorkflowStubsIntegrationTestBase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 003 (research I1, constitution V): cp-task-manager runs a task in one transaction, so the notification and
 * submission writes commit on their own; a later failure of the same task must not roll them back.
 */
class NotificationStoreIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Autowired
    private NotificationStore store;

    @Autowired
    private SubmissionStore submissionStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void state_changes_should_survive_a_rollback_of_the_task_transaction() {
        final UUID id = newNotification(UUID.randomUUID(), LocalDate.parse("2026-05-03"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            store.markProcessing(id);
            store.markStoredFailure(id, FailedStage.PROCESS, "RESULTS_UNAVAILABLE", Instant.parse("2026-10-21T10:15:00Z"));
            throw new IllegalStateException("task failed after recording");
        })).isInstanceOf(IllegalStateException.class);

        final EnforcementNotificationEntity notification = notification(id);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.STORED_FAILURE);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getFailedStage()).isEqualTo(FailedStage.PROCESS);
        assertThat(notification.getNextReplayAt()).isEqualTo(Instant.parse("2026-10-21T10:15:00Z"));
    }

    @Test
    void sending_row_should_be_committed_before_the_gob_call_even_if_the_task_rolls_back() {
        final UUID notificationId = newNotification(UUID.randomUUID(), LocalDate.parse("2026-05-03"));
        final SubmissionKey key = new SubmissionKey(notificationId, UUID.randomUUID(), LocalDate.parse("2026-05-03"), UUID.randomUUID(),
                UUID.randomUUID(), "E012345678", Instant.parse("2026-05-03T14:30:00Z"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            submissionStore.recordSending(key, "{}", List.of(new SentItem(UUID.randomUUID(), null)));
            throw new IllegalStateException("crash after the SENDING row");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findByCorrelationId(notificationId)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SENDING));
        assertThat(sentOffenceRepository.count()).isEqualTo(1);
    }

    @Test
    void ready_to_publish_should_record_the_source_and_clear_an_earlier_failure() {
        final UUID id = newNotification(UUID.randomUUID(), LocalDate.parse("2026-05-03"));
        final UUID submissionId = insertSucceeded(id);
        store.markStoredFailure(id, FailedStage.PROCESS, "X", null);

        store.markReadyToPublish(id, submissionId, FIXTURE_MASTER_DEFENDANT_ID, true);

        final EnforcementNotificationEntity notification = notification(id);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SKIPPED_NOTHING_NEW);
        assertThat(notification.getSubmissionId()).isEqualTo(submissionId);
        assertThat(notification.getGobDataReused()).isTrue();
        assertThat(notification.getFailedStage()).isNull();
        assertThat(notification.getLastError()).isNull();
        assertThat(store.submission(submissionId)).isPresent();
        assertThat(store.nowsOf(id)).hasSize(1);
    }

    @Test
    void long_error_should_be_truncated_to_the_column() {
        final UUID id = newNotification(UUID.randomUUID(), LocalDate.parse("2026-05-03"));

        store.markFinal(id, NotificationStatus.GOB_FAILED, "E".repeat(400));

        assertThat(notification(id).getLastError()).hasSize(255);
    }

    // a replayed stored failure that ends normally keeps no stale stage or replay time
    @Test
    void final_state_should_clear_an_earlier_stored_failure() {
        final UUID id = newNotification(UUID.randomUUID(), LocalDate.parse("2026-05-03"));
        store.markStoredFailure(id, FailedStage.PUBLISH, "UncategorizedJmsException", Instant.parse("2026-10-21T10:15:00Z"));

        store.markFinal(id, NotificationStatus.PUBLISHED, null);

        final EnforcementNotificationEntity notification = notification(id);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
        assertThat(notification.getFailedStage()).isNull();
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getNextReplayAt()).isNull();
    }

    // FR-009: a first share stored for a replay of stage 1 is still on its way to GOB; a final one is not
    @Test
    void first_share_stored_for_a_replay_should_still_be_in_progress() {
        final UUID hearingId = UUID.randomUUID();
        final UUID firstShare = newNotification(hearingId, LocalDate.parse("2026-05-03"));
        final UUID reshare = newNotification(hearingId, LocalDate.parse("2026-05-03"));
        final EnforcementNotificationEntity reshareEntity = notification(reshare);
        reshareEntity.setReshare(true);
        notificationRepository.saveAndFlush(reshareEntity);

        store.markStoredFailure(firstShare, FailedStage.PROCESS, "RESULTS_UNAVAILABLE", Instant.parse("2026-10-21T10:15:00Z"));
        assertThat(store.firstShareInProgress(hearingId, reshare)).isTrue();

        store.markFinal(firstShare, NotificationStatus.PUBLISHED, null);
        assertThat(store.firstShareInProgress(hearingId, reshare)).isFalse();
        assertThat(store.firstShareInProgress(UUID.randomUUID(), reshare)).isFalse();
    }

    private UUID insertSucceeded(final UUID notificationId) {
        final SubmissionKey key = new SubmissionKey(notificationId, UUID.randomUUID(), LocalDate.parse("2026-05-03"), UUID.randomUUID(),
                UUID.randomUUID(), "E012345678", Instant.parse("2026-05-03T14:30:00Z"));
        final UUID id = ((SendingRecord.Recorded) submissionStore.recordSending(key, "{}", List.of())).submissionId();
        submissionStore.recordSucceeded(id, "{\"nowsDataItems\":{}}", 200);
        return id;
    }
}
