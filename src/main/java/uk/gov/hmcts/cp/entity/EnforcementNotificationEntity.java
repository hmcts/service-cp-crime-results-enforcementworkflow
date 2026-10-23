package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One notification from the results-processing Function App for a share with enforcement NOWs (feature 003,
 * data-model.md). Holds ids, states and error codes only, never results, GOB data or personal data. Its id is the
 * {@code correlation_id} of the submissions and sent offences it creates.
 */
@Entity
@Table(name = "enforcement_notification")
@Getter
@Setter
@NoArgsConstructor
public class EnforcementNotificationEntity {

    @Id
    private UUID id;

    @Column(name = "hearing_id", nullable = false)
    private UUID hearingId;

    @Column(name = "hearing_date", nullable = false)
    private LocalDate hearingDate;

    /** An amend &amp; reshare (from the notification, research R22): never sent to GOB (FR-008). */
    @Column(name = "is_reshare", nullable = false)
    private boolean reshare;

    /** The allow-listed CJSCPPUID of the caller. */
    @Column(nullable = false, length = 36)
    private String caller;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_stage", length = 16)
    private FailedStage failedStage;

    /** An error code, HTTP status or exception class; never payload data. */
    @Column(name = "last_error")
    private String lastError;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "next_replay_at")
    private Instant nextReplayAt;

    /** The master defendant of the selected Enforcement defendant, set when GOB data is ready to publish. */
    @Column(name = "master_defendant_id")
    private UUID masterDefendantId;

    /** The submission whose GOB data is published: a new one, or the earlier one reused. */
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "gob_data_reused")
    private Boolean gobDataReused;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    /* default */ void touch() {
        final Instant now = Instant.now();
        if (receivedAt == null) {
            receivedAt = now;
        }
        updatedAt = now;
    }
}
