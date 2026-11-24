package uk.gov.hmcts.cp.entity;

/**
 * States of an {@code enforcement_result_notification} row (feature 005, data-model.md). {@link #SUPPRESSED} is
 * reachable from both {@link NotificationType#FIRST_SHARE} and {@link NotificationType#AMEND_RESHARE} (research.md
 * R9, Clarification 2026-11-24: the FR-011 trigger-shortCode gate is re-evaluated fresh on every reshare, not only
 * on a first share).
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
