package uk.gov.hmcts.cp.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the single {@link BlobContainerClient} this service owns (feature 005, research.md R8).
 * {@code DefaultAzureCredential} (Workload Identity) unconditionally for every real environment; Azurite's
 * connection string only when {@code azurite-enabled=true} (local dev/IT - Azurite has no Azure AD support). No SAS
 * tokens, no account keys (CLAUDE.md hard rule).
 *
 * <p>Creating the client opens nothing (mirrors {@link ResultsCacheConfig}'s own precedent for exactly this reason):
 * building a {@link BlobServiceClientBuilder}/{@link BlobContainerClient} never calls Azure, so this bean never
 * blocks or fails application startup even where no Azure credential is available (e.g. a plain local/CI test run
 * that boots the full Spring context without configuring Blob access at all). The container's existence is ensured
 * lazily, once, on this service's first real upload ({@link uk.gov.hmcts.cp.client.ResultNotificationBlobClient}) -
 * a transient failure there becomes a retried/{@code DOC_FAILED} row (constitution Principle V), never a crashed app.
 */
@Configuration
public class ResultNotificationBlobConfig {

    @Bean
    public BlobContainerClient resultNotificationBlobContainerClient(final ResultNotificationBlobProperties properties) {
        final BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (properties.isAzuriteEnabled()) {
            builder.connectionString(properties.getConnectionString());
        } else {
            builder.endpoint(properties.getEndpoint()).credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder.buildClient().getBlobContainerClient(properties.getContainerName());
    }
}
