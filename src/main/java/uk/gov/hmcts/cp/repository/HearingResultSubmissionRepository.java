package uk.gov.hmcts.cp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HearingResultSubmissionRepository extends JpaRepository<HearingResultSubmissionEntity, UUID> {

    Optional<HearingResultSubmissionEntity> findByHearingIdAndCaseIdAndDefendantId(UUID hearingId, UUID caseId, UUID defendantId);

    /** Stale-SENDING sweep (research.md R19), served by idx_hrs_status_updated_at. */
    @Query("select s.id from HearingResultSubmissionEntity s where s.status = :status and s.updatedAt < :cutoff order by s.updatedAt")
    List<UUID> findIdsByStatusUpdatedBefore(@Param("status") SubmissionStatus status, @Param("cutoff") Instant cutoff);

    /** Moves a row to FAILED only while it is still SENDING, so a row that has just completed is never overwritten. */
    @Modifying
    @Query("update HearingResultSubmissionEntity s set s.status = :failed, s.errorDetail = :detail, s.updatedAt = :now "
            + "where s.id = :id and s.status = :sending")
    int failIfSending(@Param("id") UUID id, @Param("detail") String detail, @Param("now") Instant now,
                      @Param("failed") SubmissionStatus failed, @Param("sending") SubmissionStatus sending);
}
