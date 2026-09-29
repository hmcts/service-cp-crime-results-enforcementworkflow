package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemName;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemRequest;
import uk.gov.hmcts.cp.openapi.model.NowsDataRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NowsDataItemSelectorTest {

    @Test
    void empty_mapping_should_request_no_nows_data() {
        assertThat(selector(Map.of()).select(List.of("SC", "WC"))).isEmpty();
    }

    @Test
    void overlapping_codes_should_request_the_unique_union_in_order() {
        final NowsDataItemSelector selector = selector(Map.of(
                "SC", List.of("Account Balance", "Account Number"),
                "WC", List.of("Account Number", "Account Warrant Number")));

        final Optional<NowsDataRequest> request = selector.select(List.of("SC", "WC"));

        assertThat(request).hasValueSatisfying(r -> assertThat(r.getNowsDataItems())
                .extracting(NowsDataItemRequest::getName)
                .containsExactly(NowsDataItemName.ACCOUNT_BALANCE, NowsDataItemName.ACCOUNT_NUMBER,
                        NowsDataItemName.ACCOUNT_WARRANT_NUMBER));
    }

    @Test
    void mapping_should_be_keyed_by_cp_short_code() {
        final NowsDataItemSelector selector = selector(Map.of("WC", List.of("Account Warrant Number")));

        assertThat(selector.select(List.of("DW"))).isEmpty(); // GOB code: not a key
        assertThat(selector.select(List.of("WC"))).isPresent();
    }

    @Test
    void codes_without_a_mapping_should_be_ignored() {
        final NowsDataItemSelector selector = selector(Map.of("SC", List.of("Account Balance")));

        assertThat(selector.select(List.of("PGPAY", "SC")))
                .hasValueSatisfying(r -> assertThat(r.getNowsDataItems()).hasSize(1));
        assertThat(selector.select(List.of("PGPAY"))).isEmpty();
    }

    private static NowsDataItemSelector selector(final Map<String, List<String>> mapping) {
        final HearingResultProperties properties = new HearingResultProperties();
        properties.getNowsDataItemsByShortCode().putAll(mapping);
        return new NowsDataItemSelector(properties);
    }
}
