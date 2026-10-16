package uk.gov.hmcts.cp.client;

/** The share's results could not be read (feature 003, research R7): the stage is retried. No payload in the message. */
public class ResultsUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResultsUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
