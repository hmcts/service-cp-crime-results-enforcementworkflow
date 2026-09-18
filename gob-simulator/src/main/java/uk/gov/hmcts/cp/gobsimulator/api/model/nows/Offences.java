package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Offences} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Offences(
        List<Offence> offence,
        BigDecimal accountTotal,
        BigDecimal accountPaid) {
}
