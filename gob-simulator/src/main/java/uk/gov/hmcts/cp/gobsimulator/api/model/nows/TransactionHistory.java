package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code TransactionHistory} in libra-gateway-hearing-events-openapi-v0.3.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionHistory(List<Transaction> transaction) {
}
