package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Offences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NowsDataItemsSerialisationTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void omits_properties_that_were_not_requested() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("accountNumber", "ACC0001"), NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"accountNumber\":\"ACC0001\"}");
    }

    @Test
    void keeps_two_decimal_places_on_monetary_values() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("accountBalance", new BigDecimal("1250.00")), NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"accountBalance\":1250.00}");
    }

    @Test
    void rejects_a_property_the_contract_does_not_declare() {
        assertThatThrownBy(() -> mapper.convertValue(
                Map.of("defendantAccount", Map.of("accountNo", "ACC0042")), NowsDataItems.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defendantAccount");
    }

    @Test
    void preserves_the_contract_spelling_of_the_payment_period_term() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("terms", Map.of("english_instalmetPaymentPeriod", "Monthly")),
                NowsDataItems.class);

        assertThat(mapper.writeValueAsString(items))
                .contains("english_instalmetPaymentPeriod")
                .doesNotContain("english_instalmentPaymentPeriod");
    }

    @Test
    void nests_offence_totals_under_offences() throws Exception {
        final NowsDataItems items = mapper.convertValue(
                Map.of("offences", Map.of("accountTotal", new BigDecimal("875.50"))),
                NowsDataItems.class);

        assertThat(items.offences()).isNotNull();
        assertThat(items.offences().accountTotal()).isEqualByComparingTo("875.50");
        assertThat(mapper.writeValueAsString(items)).isEqualTo("{\"offences\":{\"accountTotal\":875.50}}");
    }
}
