package uk.gov.hmcts.cp.service;

import java.util.UUID;

/**
 * A reshare found no GOB data while a first-time share of the same hearing is still on its way to GOB (feature 003
 * FR-009): stage 1 is retried, then stored for a replay, instead of ending NO_GOB_DATA. Ids only in the message.
 */
public class FirstShareInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FirstShareInProgressException(final UUID notificationId, final UUID hearingId) {
        super("Reshare notification " + notificationId + ": first share of hearing " + hearingId
                + " still in progress");
    }
}
