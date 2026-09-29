package uk.gov.hmcts.cp.config;

/**
 * What to send as {@code paymentTerms.paymentDueDate} when the results contain no payment terms
 * result (research.md R13). The default is {@link #NONE}: the submission is recorded as unsendable.
 * {@link #HEARING_DATE} is a stand-in for local, test and simulator runs only, pending the BA/GOB ruling.
 */
public enum PaymentDueDateFallback {
    NONE,
    HEARING_DATE
}
