package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code PaymentHistory} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentHistory(List<Payment> payment) {
}
