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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to send a defendant's newly resulted offences of one share to GOB (feature 002: each offence once). The request and response are
 * held as JSON, not a column per field, because the GOB contract is still changing. The payloads carry
 * defendant PII and are stored in plain form in iteration 1 (specs/001-cimd-4246-hearing-resulted-to-libra/adrs/001-…pii-at-rest.md).
 */
@Entity
@Table(name = "hearing_result_submission")
@Getter
@Setter
@NoArgsConstructor
public class HearingResultSubmissionEntity {

    @Id
    private UUID id;

    /** Share correlation id (feature 002 research R18): one submission per share, case and defendant. */
    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "hearing_id", nullable = false)
    private UUID hearingId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "defendant_id", nullable = false)
    private UUID defendantId;

    @Column(name = "case_urn", nullable = false, length = 36)
    private String caseUrn;

    @Column(name = "shared_time", nullable = false)
    private Instant sharedTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubmissionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private String requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload")
    private String responsePayload;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_detail")
    private String errorDetail;

    /** Outcome of the latest hand-over of GOB's reply to hearing-nows (feature 002); null = none attempted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nows_handover_status", length = 16)
    private NowsHandoverStatus nowsHandoverStatus;

    @Column(name = "nows_handover_detail")
    private String nowsHandoverDetail;

    @Column(name = "nows_handover_at")
    private Instant nowsHandoverAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    /* default */ void onCreate() {
        final Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    /* default */ void onUpdate() {
        updatedAt = Instant.now();
    }
}
