package uk.gov.hmcts.cp.entity;

/**
 * Independent Case Material attachment state of an {@code enforcement_result_notification} row (feature 005,
 * FR-020, data-model.md). Started from {@link ResultNotificationStatus#DOC_READY} alongside, never instead of, the
 * email-dispatch state machine - a Progression failure here must never affect {@link ResultNotificationStatus}, and
 * vice versa (FR-010 extended to FR-020).
 */
public enum CaseMaterialStatus {
    NOT_STARTED,
    UPLOADING,
    UPLOADED,
    ATTACHED,
    UPLOAD_FAILED,
    ATTACH_FAILED
}
