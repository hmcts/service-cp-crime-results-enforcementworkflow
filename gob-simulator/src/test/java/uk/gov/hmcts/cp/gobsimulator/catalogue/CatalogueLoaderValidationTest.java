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
 *
 * <p>Finding I5: the seven fixtures used to be stale v0.3.0-era copies (83-84 lines against
 * production's 121+), so each one tripped ~15 unintended "not known to the catalogue" errors on
 * top of its one deliberate defect — invisible here because these assertions used {@code
 * hasMessageContaining}, which is satisfied by a substring of a much longer, partly-accidental
 * message. The fixtures are now regenerated from the current production catalogue with exactly
 * one defect re-applied on top (see the fixture directories themselves), and these assertions use
 * {@code hasMessage} against the exact, complete message, so a fixture drifting stale again — or
 * a defect injection accidentally tripping a second, unintended validation error — fails the test
 * instead of being silently absorbed by a substring match.
 */
class CatalogueLoaderValidationTest {

    private static final String FIXTURES_BASE = "gob-simulator/catalogue-fixtures/";

    @Test
    void rejects_a_result_code_row_referencing_an_unknown_field_label() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "unknown-field-label/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Result code 'FSN' references unknown field label 'Bogus Unmapped Label'""");
    }

    @Test
    void rejects_an_alias_to_an_undefined_code() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "alias-to-undefined-code/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Result code 'DW' aliases unknown code 'NOPE'
                          Result code 'DW' does not resolve: Unknown resultCode: NOPE""");
    }

    @Test
    void rejects_an_unmapped_entry_with_no_reason() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "unmapped-without-reason/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Unmapped label 'Reserve Terms' has no reason""");
    }

    @Test
    void rejects_an_entity_name_target_that_is_not_a_schema_property() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "entity-name-not-a-schema-property/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Entity name 'CT Account Bank Details' targets unknown NowsDataItems property 'ctBankDetials'""");
    }

    @Test
    void rejects_an_entity_name_mapped_to_an_empty_property_list() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "entity-name-empty-list/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Entity name 'Account Balance' maps to an empty property list""");
    }

    @Test
    void rejects_a_field_path_whose_root_is_not_a_schema_property() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "field-path-root-not-a-schema-property/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Label 'Total Balance' path 'bogusRootProperty' has root property 'bogusRootProperty' which is not a known NowsDataItems property""");
    }

    @Test
    void rejects_a_catalogue_missing_a_schema_enum_result_code() {
        assertThatThrownBy(() -> new CatalogueLoader(FIXTURES_BASE + "schema-enum-code-missing-from-catalogue/").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("""
                        Invalid GOB simulator catalogue:
                          Schema resultCode enum value 'FSN' is not known to the catalogue (must be mapped, aliased, or an explicit fields: [] gap row in result-codes.yaml)""");
    }

    @Test
    void the_no_arg_constructor_still_loads_the_production_catalogue() {
        final Catalogue catalogue = new CatalogueLoader().load();
        assertThatThrownBy(() -> catalogue.propertiesFor("Not A Real NowsDataItemName"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
