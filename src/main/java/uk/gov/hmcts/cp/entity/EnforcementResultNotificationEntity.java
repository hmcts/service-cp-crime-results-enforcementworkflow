package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per unique {@code relatedReferenceNumber} per triggering share/reshare event (feature 005, data-model.md):
 * the per-recipient audit trail for CIMD-4548/CIMD-4543. {@code id} is reused end-to-end as both the doc-generator's
 * {@code sourceCorrelationId} and the gateway's {@code notificationId} (research.md R5) - never a second generated id.
 */
@Entity
@Table(name = "enforcement_result_notification")
@Getter
@Setter
@NoArgsConstructor
public class EnforcementResultNotificationEntity {

    @Id
    private UUID id;

    @Column(name = "trigger_notification_id", nullable = false)
    private UUID triggerNotificationId;

    @Column(name = "hearing_id", nullable = false)
    private UUID hearingId;

    @Column(name = "hearing_date", nullable = false)
    private LocalDate hearingDate;

    @Column(name = "related_reference_number", nullable = false, length = 64)
    private String relatedReferenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 16)
    private NotificationType notificationType;

    @Column(name = "division_code", nullable = false, length = 16)
    private String divisionCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Area area;

    @Column(name = "team_email")
    private String teamEmail;

    @Column(name = "case_urn", length = 64)
    private String caseUrn;

    /** JSON array, grouped per offence (data-model.md); retained after purge - never nulled by the purge step. */
    @Column(name = "result_short_codes")
    private String resultShortCodes;

    @Column(name = "payload_file_uri", length = 2048)
    private String payloadFileUri;

    @Column(name = "destination_file_uri", length = 2048)
    private String destinationFileUri;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultNotificationStatus status;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "purged_at")
    private Instant purgedAt;

    /** FR-020: generated once by {@code UploadCaseMaterialTask}, then reused on retry (never re-generated). */
    @Column(name = "material_id")
    private UUID materialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_material_status", nullable = false, length = 20)
    private CaseMaterialStatus caseMaterialStatus;

    /** FR-020: {@code ProsecutionCase.id()}, captured at row-creation time alongside {@code caseUrn}. */
    @Column(name = "prosecution_case_id")
    private UUID prosecutionCaseId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * FR-020: DOC_READY fans out to two fully independent tasks (email dispatch, case material attachment) that
     * write to this same row concurrently - without optimistic locking, a read-modify-save from one can silently
     * overwrite the other's concurrently-committed columns. A conflict here throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}, a {@link RuntimeException} every
     * task already retries generically - no task-level change needed.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
