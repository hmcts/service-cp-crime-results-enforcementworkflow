package uk.gov.hmcts.cp.gobsimulator.api;

/**
 * Thrown when a caller requests a {@code NowsDataItemName} outside the contract's 12-value enum.
 * Mapped by {@link GlobalExceptionHandler} to 400 + {@code ErrorResponse} — a client input
 * mistake, not the 500 a raw {@code Catalogue.propertiesFor} lookup failure would otherwise
 * surface as.
 */
public class UnknownNowsDataItemNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownNowsDataItemNameException(final String name) {
        super("Unknown NowsDataItemName: " + name);
    }
}
