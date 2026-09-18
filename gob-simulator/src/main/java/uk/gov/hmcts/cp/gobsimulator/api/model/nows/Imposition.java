package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Imposition} in libra-gateway-hearing-events-v0.4.0.yml.
 *
 * <p>{@code creditor} is deliberately omitted: no CIMD-4372 field label maps into it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Imposition(
        String impositionCode,
        String impositionType,
        String impositionText,
        String cymraeg_impositionText,
        BigDecimal amountImposed,
        BigDecimal amountPaid,
        BigDecimal impositionBalance) {
}
