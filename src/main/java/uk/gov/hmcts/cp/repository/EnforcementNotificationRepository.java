package uk.gov.hmcts.cp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EnforcementNotificationRepository extends JpaRepository<EnforcementNotificationEntity, UUID> {

    /** Whether a first-time (not reshare) notification of the hearing, other than {@code id}, is in one of the states. */
    boolean existsByHearingIdAndReshareFalseAndIdNotAndStatusIn(UUID hearingId, UUID id, Collection<NotificationStatus> statuses);

    /** Stored failures due for a replay (feature 003, research R10). */
    List<EnforcementNotificationEntity> findByStatusAndNextReplayAtLessThanEqual(NotificationStatus status, Instant now);
}
