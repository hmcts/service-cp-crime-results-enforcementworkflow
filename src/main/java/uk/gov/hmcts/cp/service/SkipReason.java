package uk.gov.hmcts.cp.service;

/** Why a hearing-resulted event produces no GOB submission. These are logged, not persisted (research.md R2/R3). */
public enum SkipReason {
    RESHARE,
    NO_ENFORCEMENT_CASE,
    MULTIPLE_ENFORCEMENT_CASES,
    NO_DEFENDANT,
    MULTIPLE_DEFENDANTS,
    LINKED_APPLICATION
}
