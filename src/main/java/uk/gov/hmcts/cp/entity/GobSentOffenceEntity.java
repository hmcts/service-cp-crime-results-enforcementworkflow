package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An offence, or a result not attached to an offence ({@code judicialResultId}), whose results were sent to GOB
 * in {@code submissionId} (feature 002, data-model §1b). Exactly one of {@code offenceId} and {@code judicialResultId}
 * is set. Written with the SENDING submission and never changed: a sent offence is never sent again (FR-018).
 */
@Entity
@Table(name = "gob_sent_offence")
@Getter
@Setter
@NoArgsConstructor
public class GobSentOffenceEntity {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "defendant_id", nullable = false)
    private UUID defendantId;

    @Column(name = "offence_id")
    private UUID offenceId;

    @Column(name = "judicial_result_id")
    private UUID judicialResultId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    /* default */ void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
