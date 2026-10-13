package uk.gov.hmcts.cp.client;

/** Outcome of one hand-over of GOB data to hearing-nows (feature 002 research R9). {@code detail} carries no PII. */
public sealed interface HandoverResult {

    record HandedOver() implements HandoverResult {
    }

    record Failed(String detail) implements HandoverResult {
    }
}
