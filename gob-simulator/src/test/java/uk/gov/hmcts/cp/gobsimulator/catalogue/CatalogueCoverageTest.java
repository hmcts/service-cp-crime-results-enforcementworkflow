package uk.gov.hmcts.cp.gobsimulator.catalogue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the catalogue against drifting away from the bundled OpenAPI contract's resultCode
 * enum. Every set asserted here is derived from the schema and the loaded catalogue rather than
 * hand-typed — {@link CatalogueLoader#schemaResultCodes()} is the same parsing path {@link
 * CatalogueLoader#load()} uses for its own startup validation, so there is exactly one place
 * that knows how to read the enum out of the contract.
 *
 * <p>"In the schema resultCode enum" and "postable" are meant to be the same set: a code CP can
 * actually send is postable, and a code marked postable: false is asserting that CP cannot send
 * it. That is a biconditional — E(c) &lt;=&gt; P(c) — and catching a regression in it requires
 * asserting both of its two independent directions, not the same implication and its
 * contrapositive relabelled:
 *
 * <ul>
 *   <li>{@link #every_schema_result_code_is_marked_postable()} asserts E(c) =&gt; P(c): a code in
 *       the enum must be postable.
 *   <li>{@link #every_catalogue_code_absent_from_the_schema_enum_is_marked_not_postable()}
 *       asserts &not;E(c) =&gt; &not;P(c): a code absent from the enum must be marked
 *       postable: false. This is the direction that actually catches a code the vendor stopped
 *       accepting (like WC/TFOUT/WWDN in v0.4.0) being left postable by omission — asserting
 *       &not;P(c) =&gt; &not;E(c) instead (the contrapositive of the first bullet, not a second
 *       direction) would not.
 * </ul>
 */
class CatalogueCoverageTest {

    private final CatalogueLoader loader = new CatalogueLoader();
    private final Catalogue catalogue = loader.load();
    private final Set<String> schemaResultCodes = loader.schemaResultCodes();

    @Test
    void every_schema_result_code_is_known_to_the_catalogue() {
        assertThat(schemaResultCodes).as("sanity: the schema must actually declare some codes").isNotEmpty();

        for (final String code : schemaResultCodes) {
            assertThat(catalogue.isKnown(code)).as("resultCode %s must be in the catalogue", code).isTrue();
        }
    }

    @Test
    void every_schema_result_code_is_marked_postable() {
        assertThat(schemaResultCodes).as("sanity: the schema must actually declare some codes").isNotEmpty();

        for (final String code : schemaResultCodes) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is in the schema resultCode enum and must not be marked postable: false", code)
                    .isTrue();
        }
    }

    @Test
    void every_catalogue_code_absent_from_the_schema_enum_is_marked_not_postable() {
        final Set<String> absentFromSchema = catalogue.allCodes().stream()
                .filter(code -> !schemaResultCodes.contains(code))
                .collect(Collectors.toSet());

        assertThat(absentFromSchema).as("sanity: at least one catalogue row must be absent from the schema enum")
                .isNotEmpty();

        for (final String code : absentFromSchema) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is absent from the schema resultCode enum and must be marked postable: false", code)
                    .isFalse();
        }
    }

    /**
     * "ENF TEXT" is the one v0.4.0 code containing a space. Named explicitly (rather than relying
     * only on the loop-based assertions above happening to cover it) so the YAML
     * quoting/round-trip and its distinctness from the unrelated "TEXT" code are self-documenting.
     */
    @Test
    void handles_the_space_containing_enf_text_code_distinctly_from_text() {
        assertThat(schemaResultCodes).contains("ENF TEXT", "TEXT");
        assertThat(catalogue.isKnown("ENF TEXT")).isTrue();
        assertThat(catalogue.isPostable("ENF TEXT")).isTrue();
        assertThat(catalogue.fieldsFor("ENF TEXT")).isEmpty();
        assertThat(catalogue.fieldsFor("TEXT")).isEmpty();
    }

    // every_field_label_used_by_a_result_code_has_a_field_path_entry was removed: it was
    // tautological. CatalogueLoader.validate() already throws in the field initializer above if
    // any result code references a field label with no field-paths.yaml entry, so this assertion
    // could never fail. CatalogueLoaderValidationTest's
    // rejects_result_code_referencing_unknown_field_label() covers that failure mode instead, by
    // loading a deliberately broken fixture and asserting validate() actually rejects it.
}
