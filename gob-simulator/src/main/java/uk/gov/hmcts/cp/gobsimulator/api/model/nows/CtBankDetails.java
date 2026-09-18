package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code CtBankDetails} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CtBankDetails(
        Integer ct_account_number,
        Integer ct_sort_code) {
}
