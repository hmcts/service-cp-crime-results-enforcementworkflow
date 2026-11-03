package uk.gov.hmcts.cp.entity;

/** The stage of a notification that failed after its retries (feature 003, research R10). */
public enum FailedStage {
    /** Results, selection, mapping and the GOB submission. */
    PROCESS,
    /** The GOB data event to hearing-nows. */
    PUBLISH
}
