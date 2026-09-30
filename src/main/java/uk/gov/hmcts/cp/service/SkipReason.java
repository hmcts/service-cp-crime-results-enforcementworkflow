package uk.gov.hmcts.cp.service;

/** Why a hearing-resulted event produces no GOB submission. These are logged, not persisted (research.md R2/R3). */
public enum SkipReason {
    RESHARE,
    /** isReshare absent: required on the event, and a first share must not be assumed (Principle IX). */
    RESHARE_FLAG_MISSING,
    NO_ENFORCEMENT_CASE,
    MULTIPLE_ENFORCEMENT_CASES,
    NO_DEFENDANT,
    MULTIPLE_DEFENDANTS,
    LINKED_APPLICATION
}
