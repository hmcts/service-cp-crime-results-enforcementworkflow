package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code cp.result-notification-blob.*} (feature 005, research.md R8): the single Blob container this service owns,
 * path-prefixed for the payload JSON and the rendered PDF (plan.md's "one container, not two" correction, confirmed
 * against {@code cpp-context-staging-dvla} branch {@code team/CHD-2786}'s {@code AzureFileStoreBlobConfiguration}).
 * {@code azurite-enabled} is local dev/IT only (Azurite has no Azure AD support); every real environment uses
 * {@code DefaultAzureCredential} (Workload Identity) against {@code endpoint} - no SAS tokens, no account keys
 * (CLAUDE.md hard rule).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp.result-notification-blob")
public class ResultNotificationBlobProperties {

    /* default */ static final String PAYLOAD_PREFIX = "result-notification/requests/";
    /* default */ static final String DOCUMENT_PREFIX = "result-notification/documents/";

    /** The Blob service endpoint, e.g. {@code https://<account>.blob.core.windows.net}; read only when not Azurite. */
    private String endpoint = "https://localhost.blob.core.windows.net";

    @NotBlank
    private String containerName = "result-notification";

    /** {@code true} only for local dev/IT (Azurite, SharedKey via {@code connectionString}); {@code false} elsewhere. */
    private boolean azuriteEnabled;

    /** Azurite-only test connection string; never set in real-environment config. */
    private String connectionString;

    private Duration connectionTimeoutSeconds = Duration.ofSeconds(10);
    private Duration responseTimeoutSeconds = Duration.ofSeconds(30);
    private Duration transferTimeoutSeconds = Duration.ofSeconds(300);

    public static String payloadBlobName(final java.util.UUID id) {
        return PAYLOAD_PREFIX + id;
    }

    public static String documentBlobName(final java.util.UUID id) {
        return DOCUMENT_PREFIX + id + ".pdf";
    }
}
