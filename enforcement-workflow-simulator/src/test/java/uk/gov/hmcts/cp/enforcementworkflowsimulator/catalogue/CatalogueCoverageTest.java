package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the catalogue against drifting away from the resultCode list Libra published in v0.6.0.
 *
 * <p>Since the LOCAL AMENDMENT (CIMD-4530, 2026-10-22) the bundled contract has no resultCode
 * enum — every CP result code is sent, and a code with no catalogue row simply contributes no
 * extra NOWS data fields. The published enum is kept as a frozen "codes GOB used before" list
 * ({@link CatalogueLoader#publishedResultCodes()}, the same parsing path {@link
 * CatalogueLoader#load()} uses for its own startup validation), and every set asserted here is
 * derived from it and the loaded catalogue rather than hand-typed.
 *
 * <p>"In the published v0.6.0 enum" and "postable" are meant to be the same set: postable marks a
 * row for a code GOB curated, and postable: false marks a row kept only as an alias target or a
 * historic spelling (WC/TFOUT/WWDN, CLAMPS/LATG/LATR/PGPAY). Since CIMD-4530 CP may still send a
 * postable: false code and the simulator accepts it like any other. That is a biconditional — E(c) &lt;=&gt; P(c) — and catching a regression in it requires
 * asserting both of its two independent directions, not the same implication and its
 * contrapositive relabelled:
 *
 * <ul>
 *   <li>{@link #every_published_result_code_is_marked_postable()} asserts E(c) =&gt; P(c): a code in
 *       the enum must be postable.
 *   <li>{@link #every_catalogue_code_absent_from_the_published_enum_is_marked_not_postable()}
 *       asserts &not;E(c) =&gt; &not;P(c): a code absent from the enum must be marked
 *       postable: false. This is the direction that actually catches a code the vendor stopped
 *       listing (like WC/TFOUT/WWDN in v0.4.0) being left postable by omission — asserting
 *       &not;P(c) =&gt; &not;E(c) instead (the contrapositive of the first bullet, not a second
 *       direction) would not.
 * </ul>
 */
class CatalogueCoverageTest {

    private final CatalogueLoader loader = new CatalogueLoader();
    private final Catalogue catalogue = loader.load();
    private final Set<String> publishedResultCodes = loader.publishedResultCodes();

    @Test
    void the_request_models_result_code_max_length_matches_the_bundled_contract() throws Exception {
        final Size size = HearingResultedRequest.HearingResult.class
                .getDeclaredField("resultCode").getAnnotation(Size.class);

        assertThat(loader.schemaResultCodeMaxLength()).isEqualTo(HearingResultedRequest.RESULT_CODE_MAX_LENGTH);
        assertThat(size).as("resultCode must carry @Size so an over-long code is a 400").isNotNull();
        assertThat(size.max()).isEqualTo(loader.schemaResultCodeMaxLength());
    }

    @Test
    void a_code_with_no_catalogue_row_contributes_no_fields_rather_than_failing() {
        assertThat(catalogue.isKnown("ZZNEWCODE")).isFalse();
        assertThat(catalogue.fieldsForPostedCode("ZZNEWCODE")).isEmpty();
        assertThat(catalogue.fieldsForPostedCode("SC")).isEqualTo(catalogue.fieldsFor("SC")).isNotEmpty();
    }

    @Test
    void every_published_result_code_is_known_to_the_catalogue() {
        assertThat(publishedResultCodes).as("sanity: the published list must actually declare some codes").isNotEmpty();

        for (final String code : publishedResultCodes) {
            assertThat(catalogue.isKnown(code)).as("resultCode %s must be in the catalogue", code).isTrue();
        }
    }

    @Test
    void every_published_result_code_is_marked_postable() {
        assertThat(publishedResultCodes).as("sanity: the published list must actually declare some codes").isNotEmpty();

        for (final String code : publishedResultCodes) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is in the published v0.6.0 resultCode enum and must not be marked postable: false", code)
                    .isTrue();
        }
    }

    @Test
    void every_catalogue_code_absent_from_the_published_enum_is_marked_not_postable() {
        final Set<String> absentFromSchema = catalogue.allCodes().stream()
                .filter(code -> !publishedResultCodes.contains(code))
                .collect(Collectors.toSet());

        assertThat(absentFromSchema).as("sanity: at least one catalogue row must be absent from the published enum")
                .isNotEmpty();

        for (final String code : absentFromSchema) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is absent from the published v0.6.0 resultCode enum and must be marked postable: false", code)
                    .isFalse();
        }
    }

    /**
     * v0.6.0 respelt the v0.4.0 code "ENF TEXT" as ENFTEXT. Named explicitly so the rename is
     * pinned (the old spelling must not linger as a postable code) and so ENFTEXT stays distinct
     * from the unrelated "TEXT" code.
     */
    @Test
    void handles_the_enftext_code_distinctly_from_text() {
        assertThat(publishedResultCodes).contains("ENFTEXT", "TEXT").doesNotContain("ENF TEXT");
        assertThat(catalogue.isKnown("ENFTEXT")).isTrue();
        assertThat(catalogue.isPostable("ENFTEXT")).isTrue();
        assertThat(catalogue.isKnown("ENF TEXT")).isFalse();
        assertThat(catalogue.fieldsFor("ENFTEXT")).isEmpty();
        assertThat(catalogue.fieldsFor("TEXT")).isEmpty();
    }

    // every_field_label_used_by_a_result_code_has_a_field_path_entry was removed: it was
    // tautological. CatalogueLoader.validate() already throws in the field initializer above if
    // any result code references a field label with no field-paths.yaml entry, so this assertion
    // could never fail. CatalogueLoaderValidationTest's
    // rejects_result_code_referencing_unknown_field_label() covers that failure mode instead, by
    // loading a deliberately broken fixture and asserting validate() actually rejects it.
}
