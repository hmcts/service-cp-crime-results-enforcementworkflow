package uk.gov.hmcts.cp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;
import uk.gov.hmcts.cp.service.NotificationRejectedException;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 003 (contract responses): mapping of rejections and storage failures; messages carry no payload values. */
class NotificationExceptionHandlerTest {

    private final NotificationExceptionHandler handler = new NotificationExceptionHandler();

    @Test
    void storage_unavailable_should_be_503_so_the_caller_retries() {
        final ResponseEntity<ErrorResponse> response = handler.unavailable(
                new DataAccessResourceFailureException("Connection to localhost refused: Edward"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getError()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().getMessage()).doesNotContain("Edward");
    }

    @Test
    void no_connection_for_the_transaction_should_be_503() {
        assertThat(handler.unavailable(new org.springframework.transaction.CannotCreateTransactionException("no connection"))
                .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void missing_or_malformed_caller_header_should_be_403_and_any_other_400() {
        final org.springframework.core.MethodParameter parameter = org.mockito.Mockito.mock(org.springframework.core.MethodParameter.class);

        assertThat(handler.missingHeader(new org.springframework.web.bind.MissingRequestHeaderException("CJSCPPUID", parameter))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.missingHeader(new org.springframework.web.bind.MissingRequestHeaderException("Other", parameter))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.typeMismatch(new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "x", java.util.UUID.class, "CJSCPPUID", parameter, null)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.typeMismatch(new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "x", java.util.UUID.class, "other", parameter, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void each_rejection_reason_should_map_to_its_status() {
        assertThat(handler.rejected(new NotificationRejectedException(NotificationRejectedException.Reason.CALLER_NOT_ALLOWED, "m"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.rejected(new NotificationRejectedException(NotificationRejectedException.Reason.INVALID, "m"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.rejected(new NotificationRejectedException(NotificationRejectedException.Reason.CONFLICT, "m"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
