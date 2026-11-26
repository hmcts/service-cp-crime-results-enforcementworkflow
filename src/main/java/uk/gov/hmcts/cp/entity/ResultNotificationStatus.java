package uk.gov.hmcts.cp.entity;

/**
 * States of an {@code enforcement_result_notification} row (feature 005, data-model.md). {@link #SUPPRESSED} is
 * reachable from {@link NotificationType#FIRST_SHARE} only (corrected 2026-11-26 against CIMD-4543's real Jira
 * acceptance criteria - AMEND_RESHARE has no shortCode gate at all, see plan.md D12, and so can never reach this
 * state).
 */
public enum ResultNotificationStatus {
    REQUESTED,
    SUPPRESSED,
    INBOX_NOT_FOUND,
    DOC_REQUESTED,
    DOC_READY,
    DOC_FAILED,
    EMAIL_REQUESTED,
    SENT,
    EMAIL_FAILED
}
