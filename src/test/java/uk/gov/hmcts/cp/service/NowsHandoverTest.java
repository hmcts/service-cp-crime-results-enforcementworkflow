package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.entity.NotificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 003 (FR-012): GOB data is marked ready to publish on the notification; never sent from here. */
class NowsHandoverTest {

    private static final UUID NOTIFICATION_ID = UUID.randomUUID();
    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final UUID MASTER_DEFENDANT_ID = UUID.randomUUID();
    private static final SubmissionKey KEY = new SubmissionKey(NOTIFICATION_ID, UUID.randomUUID(), LocalDate.parse("2026-05-03"),
            UUID.randomUUID(), UUID.randomUUID(), "E012345678", Instant.parse("2026-05-03T14:30:00Z"));

    private final NotificationStore store = mock(NotificationStore.class);
    private final NowsHandover handover = new NowsHandover(store);

    @Test
    void new_gob_data_should_be_ready_to_publish() {
        handover.handOver(SUBMISSION_ID, KEY, MASTER_DEFENDANT_ID, "26000123A", false);

        verify(store).markReadyToPublish(NOTIFICATION_ID, SUBMISSION_ID, MASTER_DEFENDANT_ID, false);
    }

    @Test
    void reused_gob_data_should_be_ready_to_publish_as_reused() {
        handover.handOver(SUBMISSION_ID, KEY, MASTER_DEFENDANT_ID, "26000123A", true);

        verify(store).markReadyToPublish(NOTIFICATION_ID, SUBMISSION_ID, MASTER_DEFENDANT_ID, true);
    }

    @Test
    void no_gob_data_should_end_the_notification_with_nothing_to_publish() {
        handover.noGobData(KEY, "RESHARE_NO_GOB_DATA");

        verify(store).markFinal(NOTIFICATION_ID, NotificationStatus.NO_GOB_DATA, "RESHARE_NO_GOB_DATA");
        verify(store, never()).markReadyToPublish(any(), any(), any(), anyBoolean());
    }

    @Test
    void reshare_should_go_on_when_no_first_share_is_in_progress() {
        when(store.firstShareInProgress(KEY.hearingId(), NOTIFICATION_ID)).thenReturn(false);

        handover.awaitFirstShare(KEY);

        verify(store, never()).markFinal(any(), any(), any());
    }

    // feature 003 FR-009: the first share may still bring GOB data, so the reshare's stage is retried, not ended
    @Test
    void reshare_should_wait_while_the_first_share_is_in_progress() {
        when(store.firstShareInProgress(KEY.hearingId(), NOTIFICATION_ID)).thenReturn(true);

        assertThatThrownBy(() -> handover.awaitFirstShare(KEY)).isInstanceOf(FirstShareInProgressException.class)
                .hasMessageContaining(NOTIFICATION_ID.toString());
        verify(store, never()).markFinal(any(), any(), any());
    }

    @Test
    void missing_account_number_should_reject_publishing() {
        handover.handOver(SUBMISSION_ID, KEY, MASTER_DEFENDANT_ID, null, false);

        verify(store).markFinal(NOTIFICATION_ID, NotificationStatus.PUBLISH_REJECTED, "NO_ACCOUNT_NUMBER");
        verify(store, never()).markReadyToPublish(any(), any(), any(), anyBoolean());
    }

    @Test
    void missing_master_defendant_should_reject_publishing() {
        handover.handOver(SUBMISSION_ID, KEY, null, "26000123A", false);

        verify(store).markFinal(NOTIFICATION_ID, NotificationStatus.PUBLISH_REJECTED, "NO_MASTER_DEFENDANT_ID");
    }
}
