package uk.gov.hmcts.cp.client;

/** The {@code generate-document} command could not be accepted (5xx, timeout, unreachable, unexpected status). */
public class DocGenerationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocGenerationUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
