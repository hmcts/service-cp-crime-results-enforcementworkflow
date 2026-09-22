package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

/**
 * Mirrors the contract's {@code ErrorResponse} schema exactly: {@code additionalProperties:
 * false}, both fields required. Returned by {@link uk.gov.hmcts.cp.enforcementworkflowsimulator.api.GlobalExceptionHandler}
 * for every error the simulator produces, so no error response ever breaks the contract it
 * exists to emulate.
 */
public record ErrorResponse(String errorCode, String errorDescription) {
}
