package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code cp.gov-uk-notify.*} (feature 005, research.md R1, R5, plan.md Config): the Azure Service Bus namespace this
 * service dispatches {@code send-email-notification} commands to (queue {@code send-email-queue}) and consumes its
 * own result events from (queue {@code reply-to-queue}). Workload Identity ({@code DefaultAzureCredential}) only -
 * no connection strings in any real environment (CLAUDE.md hard rule).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.gov-uk-notify")
public class GovUkNotifyProperties {

    /** The Service Bus namespace host, e.g. {@code <namespace>.servicebus.windows.net}. */
    @NotBlank
    private String namespace = "localhost";

    @NotBlank
    private String sendEmailQueue = "ng-send-email";

    @NotBlank
    private String replyToQueue = "enforcementworkflow-notification-result";

    /** The GOV.UK Notify templateId (a UUID, distinct from the Docmosis template id) per notification type. */
    private Map<NotificationType, UUID> templateIds = new EnumMap<>(Map.of(
            NotificationType.FIRST_SHARE, UUID.fromString("00000000-0000-0000-0000-000000000001"),
            NotificationType.AMEND_RESHARE, UUID.fromString("00000000-0000-0000-0000-000000000002")));

    public UUID templateIdFor(final NotificationType notificationType) {
        return templateIds.get(notificationType);
    }
}
