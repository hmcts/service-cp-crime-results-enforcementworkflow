package uk.gov.hmcts.cp.service;

import java.util.UUID;

/**
 * What a submission sends to GOB, recorded so it is never sent again (feature 002 FR-018): an offence (its results),
 * or a result not attached to an offence. Exactly one of the two ids is set.
 */
public record SentItem(UUID offenceId, UUID judicialResultId) {

    public static SentItem offence(final UUID offenceId) {
        return new SentItem(offenceId, null);
    }

    public static SentItem judicialResult(final UUID judicialResultId) {
        return new SentItem(null, judicialResultId);
    }
}
