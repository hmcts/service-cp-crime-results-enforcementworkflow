package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.event.HearingResultedEvent;

/** Outcome of choosing the enforcement case and defendant to submit for. */
public sealed interface SelectionResult {

    record Selected(HearingResultedEvent.ProsecutionCase prosecutionCase, HearingResultedEvent.Defendant defendant)
            implements SelectionResult {
    }

    record Skipped(SkipReason reason) implements SelectionResult {
    }
}
