package uk.gov.hmcts.cp.mapper;

import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;

/** A built request, or the reason it could not be built. {@code detail} never carries PII. */
public sealed interface MappingResult {

    record Mapped(HearingResultedRequest request) implements MappingResult {
    }

    record Failed(MappingFailureReason reason, String detail) implements MappingResult {
    }
}
