package uk.gov.hmcts.cp.integration;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R12): no embedded-broker equivalent exists for Azure Blob Storage or Azure Service Bus
 * in this repo, so every embedded-broker integration test fakes both SDK clients rather than requiring a real
 * Azure connection or an Azurite/emulator container. Imported once into {@link EmbeddedBrokerIntegrationTestBase}
 * (not per test class) so every subclass keeps sharing the same cached Spring context - a different set of
 * {@code @Import}s per test class would start a second in-VM Artemis broker.
 */
@TestConfiguration
public class ResultNotificationTestFakesConfig {

    @Bean
    @Primary
    public BlobContainerClient fakeResultNotificationBlobContainerClient() {
        final BlobContainerClient containerClient = mock(BlobContainerClient.class);
        when(containerClient.getBlobClient(anyString())).thenAnswer(invocation -> {
            final String blobName = invocation.getArgument(0);
            final BlobClient blobClient = mock(BlobClient.class);
            when(blobClient.getBlobUrl()).thenReturn("https://fake-storage.blob.core.windows.net/result-notification/" + blobName);
            when(blobClient.deleteIfExists()).thenReturn(true);
            // FR-020: UploadCaseMaterialTask reads the rendered PDF back out via downloadDocument(...)
            when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes("fake-pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return blobClient;
        });
        return containerClient;
    }

    @Bean
    @Primary
    public ServiceBusSenderClient fakeGovUkNotifyEmailSenderClient() {
        return mock(ServiceBusSenderClient.class);
    }
}
