package uk.gov.hmcts.cp.gobsimulator.catalogue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the catalogue against drifting away from the bundled OpenAPI contract's resultCode
 * enum. Both sets asserted here are derived from the schema and the loaded catalogue rather than
 * hand-typed — {@link CatalogueLoader#schemaResultCodes()} is the same parsing path {@link
 * CatalogueLoader#load()} uses for its own startup validation, so there is exactly one place that
 * knows how to read the enum out of the contract.
 */
class CatalogueCoverageTest {

    private final CatalogueLoader loader = new CatalogueLoader();
    private final Catalogue catalogue = loader.load();
    private final Set<String> schemaResultCodes = loader.schemaResultCodes();

    @Test
    void every_schema_result_code_is_known_to_the_catalogue() {
        for (final String code : schemaResultCodes) {
            assertThat(catalogue.isKnown(code)).as("resultCode %s must be in the catalogue", code).isTrue();
        }
    }

    /**
     * The inverse relationship: a code the catalogue marks {@code postable: false} is asserting
     * "the contract does not allow this" — so it must actually be absent from the schema enum, or
     * the flag would be lying. This does not hold in the other direction: a handful of legacy
     * CP-spelling codes (see result-codes.yaml's DW/TFOUT/WWDN comment) are absent from the
     * v0.4.0 enum but intentionally left postable, so we do not assert "absent from enum implies
     * not postable".
     */
    @Test
    void every_code_marked_not_postable_is_absent_from_the_schema_enum() {
        final Set<String> notPostable = catalogue.allCodes().stream()
                .filter(code -> !catalogue.isPostable(code))
                .collect(Collectors.toSet());

        assertThat(notPostable).as("expected at least one postable: false catalogue row").isNotEmpty();

        for (final String code : notPostable) {
            assertThat(schemaResultCodes)
                    .as("%s is marked postable: false and must not be in the schema resultCode enum", code)
                    .doesNotContain(code);
        }
    }

    // every_field_label_used_by_a_result_code_has_a_field_path_entry was removed: it was
    // tautological. CatalogueLoader.validate() already throws in the field initializer above if
    // any result code references a field label with no field-paths.yaml entry, so this assertion
    // could never fail. CatalogueLoaderValidationTest's
    // rejects_result_code_referencing_unknown_field_label() covers that failure mode instead, by
    // loading a deliberately broken fixture and asserting validate() actually rejects it.
}
