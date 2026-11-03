package uk.gov.hmcts.cp.service;

import java.util.UUID;

/**
 * A submission GOB accepted, as needed to reuse its GOB data (feature 002 research R16): the request sent and GOB's
 * reply, both JSON. Carries PII and GOB data: never logged.
 */
public record SucceededSubmission(UUID id, String requestPayload, String responsePayload) {
}
