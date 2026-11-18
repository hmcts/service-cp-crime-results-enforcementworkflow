package uk.gov.hmcts.cp.mapper;

import uk.gov.hmcts.cp.openapi.model.PaymentTerms;

/**
 * What a submission's payment terms results give (feature 004, data-model.md §4): no payment terms result, one block,
 * or a reason the share cannot be sent. {@code detail} names the result code and logical field, never a value.
 */
public sealed interface PaymentTermsOutcome {

    /** No payment terms result: the request is sent without {@code paymentTerms} (CIMD-4251 AC2). */
    record None() implements PaymentTermsOutcome {
    }

    record Block(PaymentTerms block) implements PaymentTermsOutcome {
    }

    record Unsendable(MappingFailureReason reason, String detail) implements PaymentTermsOutcome {
    }
}
