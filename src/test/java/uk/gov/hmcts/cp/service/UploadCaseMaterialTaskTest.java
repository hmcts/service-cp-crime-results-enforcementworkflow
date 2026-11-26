package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.ProgressionClient;
import uk.gov.hmcts.cp.client.ProgressionUnavailableException;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.CaseMaterialStatus;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature 005 FR-020: attaches the PDF to Case Material, independently of email dispatch (FR-010 extended). A
 * retry that already reached UPLOADED skips straight to the attach call rather than re-uploading the same bytes.
 */
class UploadCaseMaterialTaskTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID MATERIAL_ID = UUID.randomUUID();
    private static final UUID PROSECUTION_CASE_ID = UUID.randomUUID();
    private static final byte[] PDF_BYTES = "pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final ResultNotificationBlobClient blobClient = mock(ResultNotificationBlobClient.class);
    private final ProgressionClient progressionClient = mock(ProgressionClient.class);
    private final ResultNotificationRetries retries = new ResultNotificationRetries(new ResultNotificationProperties());
    private final UploadCaseMaterialTask task = new UploadCaseMaterialTask(store, blobClient, progressionClient, retries);

    @Test
    void missing_row_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.empty());

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(progressionClient, never()).uploadFile(any(), any());
    }

    @Test
    void already_attached_row_should_be_a_no_op() {
        when(store.find(ID)).thenReturn(Optional.of(row(CaseMaterialStatus.ATTACHED)));

        assertThat(task.execute(info(3)).getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(progressionClient, never()).uploadFile(any(), any());
        verify(progressionClient, never()).addCourtDocument(any(), any());
    }

    @Test
    void not_started_row_should_upload_then_attach() {
        when(store.find(ID)).thenReturn(Optional.of(row(CaseMaterialStatus.NOT_STARTED)));
        when(store.allocateMaterialId(ID)).thenReturn(MATERIAL_ID);
        when(blobClient.downloadDocument(ID)).thenReturn(PDF_BYTES);

        task.execute(info(3));

        verify(progressionClient).uploadFile(MATERIAL_ID, PDF_BYTES);
        verify(store).markCaseMaterialUploaded(ID);
        verify(progressionClient).addCourtDocument(MATERIAL_ID, PROSECUTION_CASE_ID);
        verify(store).markCaseMaterialAttached(ID);
    }

    // a retry that already reached UPLOADED must not re-upload the same bytes
    @Test
    void already_uploaded_row_should_skip_straight_to_attach() {
        final EnforcementResultNotificationEntity row = row(CaseMaterialStatus.UPLOADED);
        row.setMaterialId(MATERIAL_ID);
        when(store.find(ID)).thenReturn(Optional.of(row));

        task.execute(info(3));

        verify(progressionClient, never()).uploadFile(any(), any());
        verify(store, never()).allocateMaterialId(any());
        verify(progressionClient).addCourtDocument(MATERIAL_ID, PROSECUTION_CASE_ID);
        verify(store).markCaseMaterialAttached(ID);
    }

    @Test
    void upload_failure_should_be_retried_then_marked_upload_failed_on_the_last_attempt() {
        when(store.find(ID)).thenReturn(Optional.of(row(CaseMaterialStatus.NOT_STARTED)));
        when(store.allocateMaterialId(ID)).thenReturn(MATERIAL_ID);
        when(blobClient.downloadDocument(ID)).thenReturn(PDF_BYTES);
        doThrow(new ProgressionUnavailableException("broker down", null)).when(progressionClient).uploadFile(any(), any());

        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markCaseMaterialUploadFailed(eq(ID), eq("ProgressionUnavailableException"));
        verify(progressionClient, never()).addCourtDocument(any(), any());
    }

    // the row is already UPLOADED by the time the attach call fails - a retry must not re-upload. markCaseMaterialUploaded
    // is stubbed to mutate the same row object store.find(ID) keeps returning, so alreadyUploaded() sees the real
    // post-upload state, just as the real (non-mocked) store would via its own persisted column.
    @Test
    void attach_failure_should_be_retried_then_marked_attach_failed_on_the_last_attempt() {
        final EnforcementResultNotificationEntity row = row(CaseMaterialStatus.NOT_STARTED);
        when(store.find(ID)).thenReturn(Optional.of(row));
        when(store.allocateMaterialId(ID)).thenReturn(MATERIAL_ID);
        when(blobClient.downloadDocument(ID)).thenReturn(PDF_BYTES);
        doAnswer(invocation -> {
            row.setCaseMaterialStatus(CaseMaterialStatus.UPLOADED);
            return null;
        }).when(store).markCaseMaterialUploaded(ID);
        doThrow(new ProgressionUnavailableException("broker down", null)).when(progressionClient)
                .addCourtDocument(any(), any());

        final ExecutionInfo last = task.execute(info(1));

        assertThat(last.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(store).markCaseMaterialAttachFailed(eq(ID), eq("ProgressionUnavailableException"));
        verify(store, never()).markCaseMaterialUploadFailed(any(), any());
    }

    private static EnforcementResultNotificationEntity row(final CaseMaterialStatus caseMaterialStatus) {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(ID);
        entity.setCaseMaterialStatus(caseMaterialStatus);
        entity.setProsecutionCaseId(PROSECUTION_CASE_ID);
        return entity;
    }

    private static ExecutionInfo info(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jakarta.json.Json.createObjectBuilder().add("resultNotificationId", ID.toString()).build())
                .withAssignedTaskName(ResultNotificationTasks.UPLOAD_CASE_MATERIAL)
                .withAssignedTaskStartTime(ZonedDateTime.now()).withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining).build();
    }
}
