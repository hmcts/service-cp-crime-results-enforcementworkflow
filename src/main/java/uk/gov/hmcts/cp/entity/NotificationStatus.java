package uk.gov.hmcts.cp.entity;

/**
 * States of an enforcement notification (feature 003, data-model.md state machine). Stage 1 (process) ends in one of
 * {@link #SUBMITTED}, {@link #SKIPPED_NOTHING_NEW}, {@link #SKIPPED_NO_RESULT_CODE}, {@link #SKIPPED_NOT_ENFORCEMENT},
 * {@link #NO_GOB_DATA}, {@link #GOB_FAILED}; the first two continue to stage 2 (publish), which ends in
 * {@link #PUBLISHED} or {@link #PUBLISH_REJECTED}. A stage that fails after its retries is {@link #STORED_FAILURE}.
 */
public enum NotificationStatus {
    RECEIVED,
    PROCESSING,
    SUBMITTED,
    SKIPPED_NOTHING_NEW,
    SKIPPED_NO_RESULT_CODE,
    SKIPPED_NOT_ENFORCEMENT,
    NO_GOB_DATA,
    GOB_FAILED,
    PUBLISHED,
    PUBLISH_REJECTED,
    STORED_FAILURE;

    /** GOB data is ready for stage 2: a new submission GOB accepted, or earlier GOB data reused. */
    public boolean isReadyToPublish() {
        return this == SUBMITTED || this == SKIPPED_NOTHING_NEW;
    }

    /** No further processing happens without a replay. */
    public boolean isFinal() {
        return this != RECEIVED && this != PROCESSING && !isReadyToPublish();
    }
}
