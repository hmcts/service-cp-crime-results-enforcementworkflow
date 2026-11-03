package uk.gov.hmcts.cp.service;

import java.util.List;

/**
 * What is left to send to GOB for a share after {@link OffenceResultFilter} (feature 002 FR-018): the results to send,
 * the items to record as sent with the submission, and counts for the log (never ids or values).
 */
public record OffenceFilterResult(ResolvedCodes toSend, List<SentItem> sentItems, int offencesAlreadySent, int offencesAdjourned,
                                  int resultsAlreadySent) {

    /** True when nothing with a GOB code is left to send. */
    public boolean nothingToSend() {
        return toSend.gobCodes().isEmpty();
    }

    /** Counts only, for logs and the SKIPPED_NOTHING_NEW row. */
    public String summary() {
        return "offences already sent " + offencesAlreadySent + ", offences adjourned " + offencesAdjourned
                + ", other results already sent " + resultsAlreadySent;
    }
}
