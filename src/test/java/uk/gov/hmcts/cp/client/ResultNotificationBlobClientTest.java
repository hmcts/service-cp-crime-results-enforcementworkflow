package uk.gov.hmcts.cp.client;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.config.ResultNotificationBlobProperties;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R8): path-prefixed blob names within the one configured container; the destination
 * (rendered PDF) URL is only computed, never uploaded to - the doc generator writes the PDF itself.
 */
class ResultNotificationBlobClientTest {

    private static final UUID NOTIFICATION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final BlobContainerClient containerClient = mock(BlobContainerClient.class);
    private final BlobClient payloadBlobClient = mock(BlobClient.class);
    private final BlobClient documentBlobClient = mock(BlobClient.class);
    private final ResultNotificationBlobProperties properties = new ResultNotificationBlobProperties();
    private final ResultNotificationBlobClient blobClient = new ResultNotificationBlobClient(containerClient, properties);

    private void givenBlobClients() {
        when(containerClient.getBlobClient("result-notification/requests/" + NOTIFICATION_ID)).thenReturn(payloadBlobClient);
        when(containerClient.getBlobClient("result-notification/documents/" + NOTIFICATION_ID + ".pdf")).thenReturn(documentBlobClient);
    }

    @Test
    void should_upload_the_payload_with_metadata_and_tags_and_return_its_blob_url() {
        givenBlobClients();
        when(payloadBlobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/result-notification/requests/" + NOTIFICATION_ID);

        final String url = blobClient.uploadPayload(NOTIFICATION_ID, NotificationType.FIRST_SHARE, "{\"a\":1}");

        assertThat(url).isEqualTo("https://account.blob.core.windows.net/result-notification/requests/" + NOTIFICATION_ID);
        verify(containerClient).createIfNotExists();
        final ArgumentCaptor<BlobParallelUploadOptions> captor = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(payloadBlobClient).uploadWithResponse(captor.capture(), eq(properties.getTransferTimeoutSeconds()), any());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("notificationId", NOTIFICATION_ID.toString())
                .containsEntry("notificationType", "FIRST_SHARE");
        assertThat(captor.getValue().getTags()).containsEntry("notificationId", NOTIFICATION_ID.toString());
    }

    @Test
    void destination_url_should_only_compute_the_url_never_upload() {
        givenBlobClients();
        when(documentBlobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/result-notification/documents/"
                + NOTIFICATION_ID + ".pdf");

        final String url = blobClient.destinationUrl(NOTIFICATION_ID);

        assertThat(url).endsWith(NOTIFICATION_ID + ".pdf");
        verify(documentBlobClient).getBlobUrl();
        verifyNoMoreInteractions(documentBlobClient);
        verify(containerClient, org.mockito.Mockito.never()).createIfNotExists();
    }

    // building the client (ResultNotificationBlobConfig) opens nothing; this service's first real use is what
    // lazily ensures the container exists - never on every call, so startup and repeat uploads never block on it
    @Test
    void container_should_only_be_ensured_once_across_several_uploads() {
        givenBlobClients();
        when(payloadBlobClient.getBlobUrl()).thenReturn("url");

        blobClient.uploadPayload(NOTIFICATION_ID, NotificationType.FIRST_SHARE, "{}");
        blobClient.uploadPayload(NOTIFICATION_ID, NotificationType.AMEND_RESHARE, "{}");

        verify(containerClient, org.mockito.Mockito.times(1)).createIfNotExists();
    }

    // FR-020: reads the rendered PDF back out, to re-upload it to cpp-context-progression's File Store
    @Test
    void download_document_should_return_the_pdf_bytes() {
        givenBlobClients();
        final byte[] pdfBytes = "pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final BinaryData binaryData = mock(BinaryData.class);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(documentBlobClient.downloadContent()).thenReturn(binaryData);

        assertThat(blobClient.downloadDocument(NOTIFICATION_ID)).isEqualTo(pdfBytes);
    }

    @Test
    void purge_should_delete_both_blob_names() {
        givenBlobClients();

        blobClient.purge(NOTIFICATION_ID);

        verify(payloadBlobClient).deleteIfExists();
        verify(documentBlobClient).deleteIfExists();
    }
}
