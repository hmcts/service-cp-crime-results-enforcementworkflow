package uk.gov.hmcts.cp.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnforcementResultNotificationRepository extends JpaRepository<EnforcementResultNotificationEntity, UUID> {

    Optional<EnforcementResultNotificationEntity> findByTriggerNotificationIdAndRelatedReferenceNumber(
            UUID triggerNotificationId, String relatedReferenceNumber);

    /**
     * The Previous Share Snapshot (feature 005, research.md R3): the single most recent {@code SENT} row for the same
     * hearing+reference, used only for the "original" side of an AMEND_RESHARE comparison. Implemented in US2 (T052).
     */
    default Optional<EnforcementResultNotificationEntity> findPreviousShareSnapshot(final UUID hearingId,
                                                                                    final String relatedReferenceNumber) {
        return findByHearingIdAndRelatedReferenceNumberAndStatusOrderByReceivedAtDesc(
                hearingId, relatedReferenceNumber, ResultNotificationStatus.SENT, Limit.of(1))
                .stream().findFirst();
    }

    List<EnforcementResultNotificationEntity> findByHearingIdAndRelatedReferenceNumberAndStatusOrderByReceivedAtDesc(
            UUID hearingId, String relatedReferenceNumber, ResultNotificationStatus status, Limit limit);
}
