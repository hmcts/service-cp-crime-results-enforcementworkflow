package uk.gov.hmcts.cp.client;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ResultNotificationBlobProperties;
import uk.gov.hmcts.cp.entity.NotificationType;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This service's one Blob container (feature 005, research.md R8): the payload JSON is written here before calling
 * {@code generate-document}; the destination (rendered PDF) URL is only computed here, never written to - the doc
 * generator writes the PDF itself. Both blob names are path-prefixed within the single configured container
 * ({@code result-notification/requests/{id}} / {@code result-notification/documents/{id}.pdf}), mirroring
 * {@code DocumentGeneratorService.uploadDocumentToAzureBlob} in {@code cpp-context-staging-dvla} branch
 * {@code team/CHD-2786}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultNotificationBlobClient {

    private static final String METADATA_NOTIFICATION_ID = "notificationId";
    private static final String METADATA_NOTIFICATION_TYPE = "notificationType";

    private final BlobContainerClient containerClient;
    private final ResultNotificationBlobProperties properties;
    private final AtomicBoolean containerEnsured = new AtomicBoolean();

    /** Uploads the render payload JSON and returns its blob URL (used as both the entity's own column and {@code payloadFileUri}). */
    public String uploadPayload(final UUID notificationId, final NotificationType notificationType, final String payloadJson) {
        ensureContainerExists();
        final BlobClient blobClient = containerClient.getBlobClient(ResultNotificationBlobProperties.payloadBlobName(notificationId));
        final BinaryData data = BinaryData.fromBytes(payloadJson.getBytes(StandardCharsets.UTF_8));
        final BlobParallelUploadOptions options = new BlobParallelUploadOptions(data)
                .setMetadata(Map.of(METADATA_NOTIFICATION_ID, notificationId.toString(), METADATA_NOTIFICATION_TYPE,
                        notificationType.name()))
                .setTags(Map.of(METADATA_NOTIFICATION_ID, notificationId.toString()));
        blobClient.uploadWithResponse(options, properties.getTransferTimeoutSeconds(), Context.NONE);
        log.info("Uploaded result-notification payload for {} ({})", notificationId, notificationType);
        return blobClient.getBlobUrl();
    }

    /** The future PDF's blob URL - computed only, never written to (the doc generator writes the PDF itself). */
    public String destinationUrl(final UUID notificationId) {
        return containerClient.getBlobClient(ResultNotificationBlobProperties.documentBlobName(notificationId)).getBlobUrl();
    }

    /** Deletes both blobs for a row that reached {@code SENT} (FR-018); a blob that was never uploaded is a no-op. */
    public void purge(final UUID notificationId) {
        containerClient.getBlobClient(ResultNotificationBlobProperties.payloadBlobName(notificationId)).deleteIfExists();
        containerClient.getBlobClient(ResultNotificationBlobProperties.documentBlobName(notificationId)).deleteIfExists();
        log.info("Purged result-notification blobs for {}", notificationId);
    }

    // creating the client opens nothing (ResultNotificationBlobConfig); the container's existence is checked lazily
    // here on first real use - a failure here surfaces as this row's own DOC_FAILED via the calling task's normal
    // retry/failure handling, never a startup crash (constitution Principle V). The flag is set only after success,
    // so a transient failure is retried on the next call; createIfNotExists() is itself safe to call concurrently.
    private void ensureContainerExists() {
        if (!containerEnsured.get()) {
            containerClient.createIfNotExists();
            containerEnsured.set(true);
        }
    }
}
