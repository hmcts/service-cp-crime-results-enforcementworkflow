package uk.gov.hmcts.cp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.openapi.api.EnforcementNotificationApi;
import uk.gov.hmcts.cp.openapi.model.EnforcementNotification;
import uk.gov.hmcts.cp.openapi.model.NotificationAccepted;
import uk.gov.hmcts.cp.service.NotificationService;

import java.util.UUID;

/**
 * {@code POST /enforcement-notifications} from the results-processing Function App (feature 003, contract in
 * {@code api-cp-crime-results-enforcementgateway}, tag {@code enforcement-notification}). Internal network only.
 */
@RestController
@RequiredArgsConstructor
public class EnforcementNotificationController implements EnforcementNotificationApi {

    private final NotificationService notificationService;

    @Override
    @SuppressWarnings("PMD.FormalParameterNamingConventions") // name fixed by the generated interface (header CJSCPPUID)
    public ResponseEntity<NotificationAccepted> notifyEnforcementNows(final UUID CJSCPPUID,
                                                                     final EnforcementNotification enforcementNotification) {
        final UUID notificationId = notificationService.notify(CJSCPPUID, enforcementNotification);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new NotificationAccepted().notificationId(notificationId));
    }
}
