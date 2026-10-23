package uk.gov.hmcts.cp.entity;

/**
 * Outcome of one hearing-result submission to GOB (spec FR-013; data-model.md §3). {@code SENDING} is
 * the only transient status. Every other status is final, and a stale {@code SENDING} becomes
 * {@code FAILED} (research.md R19).
 */
public enum SubmissionStatus {
    SENDING,
    SUCCEEDED,
    FAILED,
    MAPPING_FAILED,
    SKIPPED_NO_RESULT_CODE,
    /** Nothing left to send: every offence already sent, or the rest adjourned (feature 002 FR-018, FR-020). */
    SKIPPED_NOTHING_NEW;

    public boolean isFinal() {
        return this != SENDING;
    }
}
