package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.openapi.model.EnforcementNotification;
import uk.gov.hmcts.cp.openapi.model.EnforcementNow;
import uk.gov.hmcts.cp.repository.EnforcementNotificationNowRepository;
import uk.gov.hmcts.cp.repository.EnforcementNotificationRepository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Accepts a notification from the results-processing Function App (feature 003, FR-002 to FR-004, FR-011, research
 * R1, R2): the caller must be an allow-listed system user; the notification and its request ids are stored and stage 1
 * is queued in one transaction, so a 202 always means the work will be done. A notification whose request ids are all
 * known is a repeat (no effect); one with only some known ids is inconsistent. Logs carry ids and counts only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationProperties properties;
    private final EnforcementNotificationRepository notificationRepository;
    private final EnforcementNotificationNowRepository nowRepository;
    private final NotificationTasks tasks;
    private final TransactionTemplate transactionTemplate;

    /**
     * @return the id of the stored notification, or of the earlier one for a repeat
     * @throws NotificationRejectedException when the caller is not allowed, the body is inconsistent, or it conflicts
     */
    public UUID notify(final UUID caller, final EnforcementNotification notification) {
        if (caller == null || !properties.isAllowed(caller.toString())) {
            throw new NotificationRejectedException(NotificationRejectedException.Reason.CALLER_NOT_ALLOWED,
                    "CJSCPPUID is not an allowed system user");
        }
        final List<UUID> requestIds = notification.getNows().stream().map(EnforcementNow::getRequestId).toList();
        if (new HashSet<>(requestIds).size() != requestIds.size()) {
            throw new NotificationRejectedException(NotificationRejectedException.Reason.INVALID, "nows has a repeated requestId");
        }
        UUID notificationId;
        try {
            notificationId = transactionTemplate.execute(status -> existingFor(requestIds, notification).orElseGet(() -> store(caller, notification)));
        } catch (DataIntegrityViolationException e) {
            // a concurrent identical notification stored the same request ids first
            notificationId = transactionTemplate.execute(status -> existingFor(requestIds, notification).orElseThrow(() -> e));
        }
        return notificationId;
    }

    /**
     * The earlier notification when the notification repeats it exactly: same hearing, date, reshare flag and the same
     * request ids with the same master defendants (FR-011); empty when no request id is known; a conflict otherwise.
     */
    private Optional<UUID> existingFor(final List<UUID> requestIds, final EnforcementNotification notification) {
        final List<EnforcementNotificationNowEntity> known = nowRepository.findByRequestIdIn(requestIds);
        Optional<UUID> existing = Optional.empty();
        if (!known.isEmpty()) {
            final Set<UUID> notificationIds = new HashSet<>();
            known.forEach(now -> notificationIds.add(now.getNotificationId()));
            if (known.size() != requestIds.size() || notificationIds.size() != 1) {
                throw new NotificationRejectedException(NotificationRejectedException.Reason.CONFLICT,
                        "some requestIds belong to an earlier notification");
            }
            existing = Optional.of(notificationIds.iterator().next());
            final boolean sameShare = notificationRepository.findById(existing.get())
                    .filter(earlier -> earlier.getHearingId().equals(notification.getHearingId())
                            && earlier.getHearingDate().equals(notification.getHearingDate())
                            && earlier.isReshare() == Boolean.TRUE.equals(notification.getIsReshare()))
                    .isPresent();
            if (!sameShare) {
                throw new NotificationRejectedException(NotificationRejectedException.Reason.CONFLICT,
                        "requestIds belong to an earlier notification for another hearing, date or reshare flag");
            }
            if (!sameNows(nowRepository.findByNotificationId(existing.get()), notification)) {
                throw new NotificationRejectedException(NotificationRejectedException.Reason.CONFLICT,
                        "requestIds belong to an earlier notification with other request ids or master defendants");
            }
            log.info("Enforcement notification {} repeated ({} NOW request(s)); no effect", existing.get(), requestIds.size());
        }
        return existing;
    }

    private static boolean sameNows(final List<EnforcementNotificationNowEntity> earlier, final EnforcementNotification notification) {
        final Set<String> stored = new HashSet<>();
        earlier.forEach(now -> stored.add(now.getRequestId() + ":" + now.getMasterDefendantId()));
        final Set<String> received = new HashSet<>();
        notification.getNows().forEach(now -> received.add(now.getRequestId() + ":" + now.getMasterDefendantId()));
        return stored.equals(received);
    }

    private UUID store(final UUID caller, final EnforcementNotification notification) {
        final EnforcementNotificationEntity entity = new EnforcementNotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setHearingId(notification.getHearingId());
        entity.setHearingDate(notification.getHearingDate());
        entity.setReshare(Boolean.TRUE.equals(notification.getIsReshare()));
        entity.setCaller(caller.toString());
        entity.setStatus(NotificationStatus.RECEIVED);
        notificationRepository.saveAndFlush(entity);
        // in request id order: concurrent notifications sharing ids then lock their rows in the same order (no deadlock)
        notification.getNows().stream().sorted(Comparator.comparing(EnforcementNow::getRequestId))
                .forEach(now -> nowRepository.saveAndFlush(
                        new EnforcementNotificationNowEntity(now.getRequestId(), entity.getId(), now.getMasterDefendantId())));
        tasks.queueProcess(entity.getId());
        log.info("Enforcement notification {} received for hearing {} on {} (reshare {}, {} NOW request(s)); queued",
                entity.getId(), entity.getHearingId(), entity.getHearingDate(), entity.isReshare(), notification.getNows().size());
        return entity.getId();
    }
}
