package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.entity.NotificationStatus;

import java.util.UUID;

/**
 * Marks a notification's GOB data ready for the GOB data event (feature 003, FR-012; replaces feature 002's direct
 * hand-over call). The key's correlation id is the notification id. Stage 2 builds the event from the source
 * submission, so publishing (and replaying it) never contacts GOB. Without a master defendant or an account number
 * nothing can be published: the notification ends {@link NotificationStatus#PUBLISH_REJECTED}. Logs carry ids only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NowsHandover {

    private final NotificationStore notificationStore;

    /**
     * A reshare waits while a first-time share of the hearing is still on its way to GOB (feature 003 FR-009): that share
     * may bring the GOB data the reshare's NOWS need, so the reshare's stage is retried rather than ending without it.
     *
     * @throws FirstShareInProgressException when a first-time share of the hearing is still in progress
     */
    public void awaitFirstShare(final SubmissionKey key) {
        if (notificationStore.firstShareInProgress(key.hearingId(), key.correlationId())) {
            throw new FirstShareInProgressException(key.correlationId(), key.hearingId());
        }
    }

    /** Nothing to publish: no GOB data for the share (feature 003 FR-009); its NOWS stay held (FR-016). */
    public void noGobData(final SubmissionKey key, final String reason) {
        notificationStore.markFinal(key.correlationId(), NotificationStatus.NO_GOB_DATA, reason);
        log.info("Notification {} caseUrn {}: no GOB data to publish ({}); NOWS stay held", key.correlationId(), key.caseUrn(), reason);
    }

    /**
     * @param sourceSubmissionId the submission GOB accepted whose reply is published (new, or reused)
     * @param gobDataReused      true when GOB was not contacted for this share (FR-009)
     */
    public void handOver(final UUID sourceSubmissionId, final SubmissionKey key, final UUID masterDefendantId, final String accountNumber,
                         final boolean gobDataReused) {
        final UUID notificationId = key.correlationId();
        if (masterDefendantId == null || accountNumber == null) {
            final String reason = masterDefendantId == null ? "NO_MASTER_DEFENDANT_ID" : "NO_ACCOUNT_NUMBER";
            notificationStore.markFinal(notificationId, NotificationStatus.PUBLISH_REJECTED, reason);
            log.warn("Notification {} caseUrn {}: GOB data not publishable ({})", notificationId, key.caseUrn(), reason);
        } else {
            notificationStore.markReadyToPublish(notificationId, sourceSubmissionId, masterDefendantId, gobDataReused);
            log.info("Notification {} caseUrn {}: GOB data of submission {} ready to publish (reused {})", notificationId,
                    key.caseUrn(), sourceSubmissionId, gobDataReused);
        }
    }
}
