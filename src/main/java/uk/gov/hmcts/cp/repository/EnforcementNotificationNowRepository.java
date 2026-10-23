package uk.gov.hmcts.cp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EnforcementNotificationNowRepository extends JpaRepository<EnforcementNotificationNowEntity, UUID> {

    List<EnforcementNotificationNowEntity> findByNotificationId(UUID notificationId);

    List<EnforcementNotificationNowEntity> findByRequestIdIn(Collection<UUID> requestIds);
}
