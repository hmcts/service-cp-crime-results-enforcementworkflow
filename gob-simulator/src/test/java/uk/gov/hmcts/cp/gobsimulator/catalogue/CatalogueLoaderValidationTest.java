package uk.gov.hmcts.cp.gobsimulator.catalogue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@code CatalogueLoader.load()} actually rejects a broken catalogue at startup,
 * rather than merely proving the good catalogue still loads. Each fixture under
 * {@code gob-simulator/catalogue-fixtures/<scenario>/} is a full copy of the production catalogue
 * with exactly one deliberate defect, validated against the one bundled production OpenAPI
 * contract (only the catalogue base directory is swapped, via the test-only
 * {@link CatalogueLoader#CatalogueLoader(String)} constructor).
 */
class CatalogueLoaderValidationTest {

    private static final String FIXTURES_BASE = "gob-simulator/catalogue-fixtures/";

    @Test
    void rejects_a_result_code_row_referencing_an_unknown_field_label() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "unknown-field-label/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FSN")
                .hasMessageContaining("unknown field label")
                .hasMessageContaining("Bogus Unmapped Label");
    }

    @Test
    void rejects_an_alias_to_an_undefined_code() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "alias-to-undefined-code/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DW")
                .hasMessageContaining("aliases unknown code")
                .hasMessageContaining("NOPE");
    }

    @Test
    void rejects_an_unmapped_entry_with_no_reason() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "unmapped-without-reason/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reserve Terms")
                .hasMessageContaining("has no reason");
    }

    @Test
    void rejects_an_entity_name_target_that_is_not_a_schema_property() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "entity-name-not-a-schema-property/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CT Account Bank Details")
                .hasMessageContaining("targets unknown NowsDataItems property")
                .hasMessageContaining("ctBankDetials");
    }

    @Test
    void rejects_an_entity_name_mapped_to_an_empty_property_list() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "entity-name-empty-list/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Account Balance")
                .hasMessageContaining("empty property list");
    }

    @Test
    void rejects_a_field_path_whose_root_is_not_a_schema_property() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "field-path-root-not-a-schema-property/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Total Balance")
                .hasMessageContaining("bogusRootProperty")
                .hasMessageContaining("not a known NowsDataItems property");
    }

    @Test
    void rejects_a_catalogue_missing_a_schema_enum_result_code() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "schema-enum-code-missing-from-catalogue/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FSN")
                .hasMessageContaining("is not known to the catalogue");
    }

    @Test
    void the_no_arg_constructor_still_loads_the_production_catalogue() {
        final Catalogue catalogue = new CatalogueLoader().load();
        assertThatThrownBy(() -> catalogue.propertiesFor("Not A Real NowsDataItemName"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
