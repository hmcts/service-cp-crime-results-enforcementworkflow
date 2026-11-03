package uk.gov.hmcts.cp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.entity.GobSentOffenceEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface GobSentOffenceRepository extends JpaRepository<GobSentOffenceEntity, UUID> {

    List<GobSentOffenceEntity> findByCaseIdAndOffenceIdIn(UUID caseId, Collection<UUID> offenceIds);

    List<GobSentOffenceEntity> findByCaseIdAndJudicialResultIdIn(UUID caseId, Collection<UUID> judicialResultIds);
}
