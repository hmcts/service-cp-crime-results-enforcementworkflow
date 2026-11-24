package uk.gov.hmcts.cp.service;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes this service's own Azure Service Bus reply queue ({@code enforcementworkflowresult-notification-result})
 * for the gateway's bare ASB {@code subject} values (feature 005, research.md R1 - <b>not</b>
 * {@code public.notificationnotify.events.*}, a legacy-naming correction): {@code notification-sent} ->
 * {@code SENT} (then, and only then, purges both blobs - FR-018); {@code notification-failed} and
 * {@code notification-validation-failed} -> {@code EMAIL_FAILED}. {@code notification-validation-failed} carries
 * no {@code notificationId} (the command was never accepted) - it correlates through its own echoed
 * {@code originalEventPayload}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultNotificationReplyListener {

    /* default */ static final String SUBJECT_NOTIFICATION_SENT = "notification-sent";
    /* default */ static final String SUBJECT_NOTIFICATION_FAILED = "notification-failed";
    /* default */ static final String SUBJECT_NOTIFICATION_VALIDATION_FAILED = "notification-validation-failed";

    private final ResultNotificationStore store;
    private final NotificationStore triggerStore;
    private final ResultNotificationBlobClient blobClient;
    private final ObjectMapper objectMapper;

    public void onMessage(final ServiceBusReceivedMessageContext context) {
        handle(context.getMessage().getSubject(), context.getMessage().getBody().toString());
        context.complete();
    }

    public void handle(final String subject, final String body) {
        if (SUBJECT_NOTIFICATION_SENT.equals(subject)) {
            handleSent(body);
        } else if (SUBJECT_NOTIFICATION_FAILED.equals(subject)) {
            handleFailed(body);
        } else if (SUBJECT_NOTIFICATION_VALIDATION_FAILED.equals(subject)) {
            handleValidationFailed(body);
        } else {
            log.warn("Unrecognised cp-gov-uk-notify-gateway reply subject: {}", subject);
        }
    }

    private void handleSent(final String body) {
        final NotificationSent event = objectMapper.readValue(body, NotificationSent.class);
        final UUID id = UUID.fromString(event.notificationId());
        store.find(id).ifPresent(row -> {
            store.markSent(id);
            logSent(row);
            blobClient.purge(id);
            store.markPurged(id);
        });
    }

    private void handleFailed(final String body) {
        final NotificationFailed event = objectMapper.readValue(body, NotificationFailed.class);
        store.markEmailFailed(UUID.fromString(event.notificationId()), event.errorMessage());
    }

    private void handleValidationFailed(final String body) {
        final NotificationValidationFailed event = objectMapper.readValue(body, NotificationValidationFailed.class);
        log.warn("send-email-notification rejected: {} - {}", event.reason(), event.details());
        originalNotificationId(event.originalEventPayload())
                .ifPresent(id -> store.markEmailFailed(id, event.reason() + ": " + event.details()));
    }

    private Optional<UUID> originalNotificationId(final String originalEventPayload) {
        Optional<UUID> id;
        try {
            final OriginalCommand command = objectMapper.readValue(originalEventPayload, OriginalCommand.class);
            id = command.notificationId() == null ? Optional.empty() : Optional.of(UUID.fromString(command.notificationId()));
        } catch (RuntimeException e) {
            log.warn("notification-validation-failed originalEventPayload not readable: {}", e.getClass().getSimpleName());
            id = Optional.empty();
        }
        return id;
    }

    // FR-016: every SENT outcome is logged with caseUrn, Division Code, recipient team email, shortCodes, the
    // sharing user's identifier, and an ISO-8601 UTC timestamp
    private void logSent(final EnforcementResultNotificationEntity row) {
        final String caller = triggerStore.find(row.getTriggerNotificationId())
                .map(uk.gov.hmcts.cp.entity.EnforcementNotificationEntity::getCaller).orElse(null);
        log.info("Enforcement result notification {} SENT: caseUrn={}, divisionCode={}, teamEmail={}, "
                + "resultShortCodes={}, sharingUser={}, at={}", row.getId(), row.getCaseUrn(), row.getDivisionCode(),
                row.getTeamEmail(), row.getResultShortCodes(), caller, Instant.now());
    }

    /* default */ record NotificationSent(String notificationId, String sentTime) {
    }

    /* default */ record NotificationFailed(String notificationId, String failedTime, String errorMessage) {
    }

    /* default */ record NotificationValidationFailed(String reason, String details, String originalEventPayload) {
    }

    /* default */ record OriginalCommand(String notificationId) {
    }
}
