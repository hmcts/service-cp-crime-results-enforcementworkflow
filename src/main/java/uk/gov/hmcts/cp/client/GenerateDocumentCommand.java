package uk.gov.hmcts.cp.client;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code generate-document} BYO-FS command (feature 005, contracts/system-doc-generator-byo-fs.md): this service
 * always supplies both {@code payloadFileUri} (BYO-FS) and {@code destinationFileUri}, never a legacy
 * {@code payloadFileServiceId}. Completion is event-driven (research.md R2), not a response to this call.
 */
public record GenerateDocumentCommand(String templateIdentifier, String conversionFormat, Instant requestedTime,
                                      String payloadFileUri, String destinationFileUri, String originatingSource,
                                      UUID sourceCorrelationId) {

    public static final String CONVERSION_FORMAT_PDF = "pdf";
}
