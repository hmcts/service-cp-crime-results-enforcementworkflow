package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Payment} in libra-gateway-hearing-events-openapi-v0.3.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Payment(
        String paymentDate,
        String paymentEventCode,
        String paymentType,
        BigDecimal paymentAmount,
        String creditOrDebit) {
}
