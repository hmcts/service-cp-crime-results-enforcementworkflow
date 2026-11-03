package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * The request id of one enforcement NOW request named in a notification (feature 003, data-model.md). The request id
 * is the primary key: the same NOW request can belong to one notification only. A new instance is always inserted
 * ({@link Persistable}), so a request id already stored fails on the primary key instead of being moved to another
 * notification by a merge (constitution III: idempotency enforced by the database).
 */
@Entity
@Table(name = "enforcement_notification_now")
@Getter
@Setter
@NoArgsConstructor
public class EnforcementNotificationNowEntity implements Persistable<UUID> {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "master_defendant_id", nullable = false)
    private UUID masterDefendantId;

    @Transient
    private boolean persisted;

    public EnforcementNotificationNowEntity(final UUID requestId, final UUID notificationId, final UUID masterDefendantId) {
        this.requestId = requestId;
        this.notificationId = notificationId;
        this.masterDefendantId = masterDefendantId;
    }

    @Override
    public UUID getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    /* default */ void markPersisted() {
        persisted = true;
    }
}
