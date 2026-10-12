package uk.gov.hmcts.cp.entity;

/** Outcome of handing GOB's reply over to hearing-nows (feature 002, data-model §1). Null on a row means no attempt. */
public enum NowsHandoverStatus {
    HANDED_OVER,
    FAILED
}
