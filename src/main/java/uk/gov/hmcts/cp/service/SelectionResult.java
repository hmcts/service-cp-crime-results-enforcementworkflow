package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.event.HearingResultedEvent;

/** Outcome of choosing the enforcement case and defendant to submit for. */
public sealed interface SelectionResult {

    record Selected(HearingResultedEvent.ProsecutionCase prosecutionCase, HearingResultedEvent.Defendant defendant)
            implements SelectionResult {
    }

    record Skipped(SkipReason reason) implements SelectionResult {
    }

    /**
     * An amend &amp; reshare of an in-scope Enforcement case (feature 002 FR-016): never sent to GOB; the most recent GOB
     * data for the case and defendant is handed over again.
     */
    record Reshare(HearingResultedEvent.ProsecutionCase prosecutionCase, HearingResultedEvent.Defendant defendant)
            implements SelectionResult {
    }
}
