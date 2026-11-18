package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Terms} in libra-gateway-hearing-events-v0.8.0.yml.
 *
 * <p>{@code english_instalmentPaymentPeriod}: v0.8.0 corrected the earlier vendor misspelling
 * {@code english_instalmetPaymentPeriod} (up to v0.6.0).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Terms(
        String english_due,
        String english_instalment,
        String english_lumpsum,
        String english_instalmentPaymentPeriod,
        String english_firstDate,
        String cymraeg_due,
        String cymraeg_instalment,
        String cymraeg_lumpsum,
        String cymraeg_instalmentPaymentPeriod,
        String cymraeg_firstDate) {
}
