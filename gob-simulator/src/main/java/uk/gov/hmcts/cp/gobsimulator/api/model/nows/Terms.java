package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Terms} in libra-gateway-hearing-events-openapi-v0.3.0.yml.
 *
 * <p>{@code english_instalmetPaymentPeriod} preserves the vendor schema's own misspelling
 * of "instalment" verbatim; it is not a typo in this codebase.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Terms(
        String english_due,
        String english_instalment,
        String english_lumpsum,
        String english_instalmetPaymentPeriod,
        String english_firstDate,
        String cymraeg_due,
        String cymraeg_instalment,
        String cymraeg_lumpsum,
        String cymraeg_instalmentPaymentPeriod,
        String cymraeg_firstDate) {
}
