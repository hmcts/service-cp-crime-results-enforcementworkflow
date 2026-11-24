package uk.gov.hmcts.cp.client;

/** The {@code send-email-notification} command could not be sent to the Service Bus queue. */
public class GovUkNotifyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GovUkNotifyUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
