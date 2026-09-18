package uk.gov.hmcts.cp.gobsimulator.seed;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.gobsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.gobsimulator.catalogue.CatalogueLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ValueResolverTest {

    private final Catalogue catalogue = new CatalogueLoader().load();
    private final ValueResolver resolver = new ValueResolver(new SeedStore(""));

    @Test
    void falls_back_to_the_catalogue_default_when_the_case_urn_is_unseeded() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Account No.")))
                .isEqualTo("ACC0001");
    }

    @Test
    void returns_monetary_defaults_as_big_decimal_with_two_decimal_places() {
        final Object balance = resolver.resolve("E999999999", catalogue.fieldPath("Total Balance"));

        assertThat(balance).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) balance).scale()).isEqualTo(2);
        assertThat(balance).isEqualTo(new BigDecimal("1250.00"));
    }

    @Test
    void uses_the_warrant_number_format_the_contract_declares_not_the_ticket_example() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Warrant No")))
                .isEqualTo("012/26/00123")
                .asString().matches("^\\d{3}/\\d{2}/\\d{5}$");
    }

    @Test
    void uses_the_date_format_the_contract_declares_not_the_ticket_example() {
        assertThat(resolver.resolve("E999999999", catalogue.fieldPath("Date Imposed")))
                .isEqualTo("15 Jan 2026");
    }

    @Test
    void prefers_a_seeded_value_over_the_default() {
        assertThat(resolver.resolve("E011122334", catalogue.fieldPath("Account No.")))
                .isEqualTo("ACC9001");
    }

    @Test
    void reads_a_seeded_value_from_a_nested_path() {
        assertThat(resolver.resolve("E011122334", catalogue.fieldPath("Balance Outstanding")))
                .isEqualTo(new BigDecimal("340.00"));
    }
}
