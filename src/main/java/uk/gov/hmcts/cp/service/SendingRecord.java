package uk.gov.hmcts.cp.service;

import java.util.UUID;

/** Outcome of recording a submission as SENDING (and its sent items) before the gateway call. */
public sealed interface SendingRecord {

    /** Recorded: the call may go ahead. */
    record Recorded(UUID submissionId) implements SendingRecord {
    }

    /** A row for the same share, case and defendant exists (a repeated or concurrent delivery): not sent again. */
    record AlreadyRecorded() implements SendingRecord {
    }

    /** One of the items was sent by another share meanwhile: nothing recorded; filter again (FR-018). */
    record ItemsAlreadySent() implements SendingRecord {
    }
}
