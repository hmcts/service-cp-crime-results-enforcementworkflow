package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

/**
 * Thrown when a caller posts a {@code resultCode} outside the bundled contract's {@code
 * resultCode} enum. Mapped by {@link GlobalExceptionHandler} to 400 + {@code ErrorResponse} — a
 * client input mistake, not the 500 a raw {@code Catalogue.resolve} lookup failure would
 * otherwise surface as (Task 8, ruling 3 — the same defect class Task 7 fixed for {@code
 * NowsDataItemName}, but that fix only ever covered the name field).
 */
public class UnknownResultCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownResultCodeException(final String resultCode) {
        super("Unknown resultCode: " + resultCode);
    }
}
