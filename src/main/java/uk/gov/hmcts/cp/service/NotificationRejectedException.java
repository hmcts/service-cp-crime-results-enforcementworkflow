package uk.gov.hmcts.cp.service;

import lombok.Getter;

/** A notification that is not accepted (feature 003 contract): the message never carries payload values. */
@Getter
public class NotificationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason { CALLER_NOT_ALLOWED, INVALID, CONFLICT }

    private final Reason reason;

    public NotificationRejectedException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }
}
