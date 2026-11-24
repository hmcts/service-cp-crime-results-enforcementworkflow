package uk.gov.hmcts.cp.config;

import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 005 (research.md R8): building the client opens nothing (mirrors {@link ResultsCacheConfig}'s own
 * precedent) in either credential mode - Azurite's connection string (local dev/IT) or
 * {@code DefaultAzureCredential} (every real environment, Workload Identity).
 */
class ResultNotificationBlobConfigTest {

    private final ResultNotificationBlobConfig config = new ResultNotificationBlobConfig();

    @Test
    void should_build_with_azurite_connection_string_when_enabled() {
        final ResultNotificationBlobProperties properties = new ResultNotificationBlobProperties();
        properties.setAzuriteEnabled(true);
        properties.setConnectionString("UseDevelopmentStorage=true");
        properties.setContainerName("result-notification");

        final BlobContainerClient client = config.resultNotificationBlobContainerClient(properties);

        assertThat(client).isNotNull();
    }

    @Test
    void should_build_with_default_azure_credential_when_azurite_disabled() {
        final ResultNotificationBlobProperties properties = new ResultNotificationBlobProperties();
        properties.setAzuriteEnabled(false);
        properties.setEndpoint("https://account.blob.core.windows.net");
        properties.setContainerName("result-notification");

        final BlobContainerClient client = config.resultNotificationBlobContainerClient(properties);

        assertThat(client).isNotNull();
    }
}
