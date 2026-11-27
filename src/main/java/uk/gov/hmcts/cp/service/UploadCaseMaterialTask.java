package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.ProgressionClient;
import uk.gov.hmcts.cp.client.ResultNotificationBlobClient;
import uk.gov.hmcts.cp.entity.CaseMaterialStatus;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import static uk.gov.hmcts.cp.service.ResultNotificationTasks.UPLOAD_CASE_MATERIAL;
import static uk.gov.hmcts.cp.service.ResultNotificationTasks.resultNotificationIdOf;

/**
 * Feature 005 FR-020: attaches the rendered PDF to the case's Case Material section, queued alongside (never
 * instead of, and never affecting) {@link DispatchResultNotificationEmailTask} - FR-010's independence principle
 * extended to this step. Two calls via {@link ProgressionClient}: upload the bytes (idempotent on retry - the
 * generated {@code material_id} is reused, never re-generated), then attach to the case. A retry that already
 * reached {@code UPLOADED} skips straight to the attach call rather than re-uploading the same bytes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(UPLOAD_CASE_MATERIAL)
public class UploadCaseMaterialTask implements ExecutableTask {

    private final ResultNotificationStore store;
    private final ResultNotificationBlobClient blobClient;
    private final ProgressionClient progressionClient;
    private final ResultNotificationRetries retries;

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID id = resultNotificationIdOf(executionInfo.getJobData());
        ExecutionInfo next;
        try {
            next = upload(executionInfo, id);
        } catch (RuntimeException e) {
            log.error("Enforcement result notification {} case material upload failed ({})", id, e.getClass().getSimpleName());
            final BiConsumer<UUID, String> storeFailure = alreadyUploaded(id)
                    ? store::markCaseMaterialAttachFailed : store::markCaseMaterialUploadFailed;
            next = retries.retryOrFail(executionInfo, id, e.getClass().getSimpleName(), storeFailure);
        }
        return next;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return retries.retryDurationsInSecs();
    }

    private ExecutionInfo upload(final ExecutionInfo executionInfo, final UUID id) {
        final Optional<EnforcementResultNotificationEntity> row = store.find(id);
        final ExecutionInfo next;
        if (row.isEmpty() || row.get().getCaseMaterialStatus() == CaseMaterialStatus.ATTACHED) {
            log.info("Enforcement result notification {}: nothing to attach to case material ({})", id,
                    row.map(EnforcementResultNotificationEntity::getCaseMaterialStatus).orElse(null));
            next = ResultNotificationRetries.completed(executionInfo);
        } else {
            final EnforcementResultNotificationEntity entity = row.get();
            final UUID materialId = entity.getCaseMaterialStatus() == CaseMaterialStatus.UPLOADED
                    ? entity.getMaterialId() : uploadFile(id);
            progressionClient.addCourtDocument(materialId, entity.getProsecutionCaseId());
            store.markCaseMaterialAttached(id);
            next = ResultNotificationRetries.completed(executionInfo);
        }
        return next;
    }

    private UUID uploadFile(final UUID id) {
        final UUID materialId = store.allocateMaterialId(id);
        progressionClient.uploadFile(materialId, blobClient.downloadDocument(id));
        store.markCaseMaterialUploaded(id);
        return materialId;
    }

    private boolean alreadyUploaded(final UUID id) {
        return store.find(id).map(row -> row.getCaseMaterialStatus() == CaseMaterialStatus.UPLOADED).orElse(false);
    }
}
