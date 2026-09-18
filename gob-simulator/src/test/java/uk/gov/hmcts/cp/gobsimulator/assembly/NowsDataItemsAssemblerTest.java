package uk.gov.hmcts.cp.gobsimulator.assembly;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.gobsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.gobsimulator.catalogue.CatalogueLoader;
import uk.gov.hmcts.cp.gobsimulator.seed.SeedStore;
import uk.gov.hmcts.cp.gobsimulator.seed.ValueResolver;

import static org.assertj.core.api.Assertions.assertThat;

class NowsDataItemsAssemblerTest {

    private final NowsDataItemsAssembler assembler = new NowsDataItemsAssembler(
            new CatalogueLoader().load(),
            new ValueResolver(new SeedStore("")),
            new ObjectMapper());

    @Test
    void returns_only_the_entities_that_were_requested() {
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("SC"), List.of("Account Balance", "Account Number"));

        assertThat(items.accountBalance()).isEqualByComparingTo("1250.00");
        assertThat(items.accountNumber()).isEqualTo("ACC0001");
        assertThat(items.defendant()).isNull();
        assertThat(items.terms()).isNull();
    }

    @Test
    void emits_a_requested_entity_even_when_no_posted_code_contributes_a_field_to_it() {
        // FSN requires only Account No. and Total Balance — nothing lands in warrantContactDetails.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("FSN"), List.of("Warrant Contact Details"));

        assertThat(items.warrantContactDetails())
                .as("AC2 forbids missing keys — the entity must still be present")
                .isNotNull();
    }

    // Item 2 (final wave): defaultFor() unions only baseline: true rows for an object-typed root
    // when no posted code touches it at all (Finding I4). Two of the five object-typed roots —
    // terms and warrantContactDetails — had zero baseline rows, so a requested-but-untouched
    // instance of either came back as {}. posting_an_additional_fields_empty_code_never_
    // shrinks_an_entity above only ever asserts fieldsWithBoth >= fieldsWithAcnoteAlone, which
    // passes trivially at 0 >= 0 for an entity that is empty both times — it would not have
    // caught this. This test is the one that would notice emptiness, for every object-typed root
    // at once. NOENF is a real resultCode with no CIMD-4372 mapping (fields: []), so nothing it
    // posts touches any of these five entities.
    @Test
    void a_requested_but_untouched_object_typed_entity_is_never_empty() {
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("NOENF"),
                List.of("Defendant Account", "Account Offences and Penalties", "Account Terms to Pay",
                        "Warrant Contact Details", "CT Account Bank Details"));

        final ObjectMapper mapper = new ObjectMapper();
        assertNeverEmpty(mapper, "defendant", items.defendant());
        assertNeverEmpty(mapper, "offences", items.offences());
        assertNeverEmpty(mapper, "terms", items.terms());
        assertNeverEmpty(mapper, "warrantContactDetails", items.warrantContactDetails());
        assertNeverEmpty(mapper, "ctBankDetails", items.ctBankDetails());
    }

    private static void assertNeverEmpty(final ObjectMapper mapper, final String entityName, final Object entity) {
        assertThat(entity).as("%s must be present (AC2)", entityName).isNotNull();
        final Map<?, ?> asMap = mapper.convertValue(entity, Map.class);
        assertThat(asMap)
                .as("%s must never be emitted as {} for a requested-but-untouched entity", entityName)
                .isNotEmpty();
    }

    @Test
    void unions_required_fields_across_several_posted_codes() {
        // Task 8, ruling 2: this test previously used ABDC (Balance Outstanding -> accountTotal)
        // + CW (Amount Paid or Cancelled -> accountPaid), asserting both are non-null. But
        // accountTotal/accountPaid are BOTH schema-required on Offences, and Task 8's assembler
        // fix (ruling 1) merges baseline-flagged defaults into a partially-populated entity to
        // satisfy exactly that kind of schema-required gap. Once that fix landed, ABDC alone would
        // already leave neither sub-field null — the test would still pass, but prove nothing about
        // whether the union across codes actually happened.
        //
        // Rewritten onto a pair of NON-required fields the baseline merge deliberately never
        // fills: SUMM supplies "Imposition type" (offence[0].impositions.imposition[0]
        // .impositionType) and does NOT supply "Place of offence"; S136 supplies "Place of
        // offence" (offence[0].placeOfOffence) and does NOT supply "Imposition type". Neither
        // field is schema-required, so a negative control demonstrates this is falsifiable: SUMM
        // alone must leave placeOfOffence null, S136 alone must leave impositionType null, and
        // only posting both together must leave neither null.
        final NowsDataItems summOnly = assembler.assemble(
                "E999999999", List.of("SUMM"), List.of("Account Offences and Penalties"));
        final NowsDataItems s136Only = assembler.assemble(
                "E999999999", List.of("S136"), List.of("Account Offences and Penalties"));
        final NowsDataItems both = assembler.assemble(
                "E999999999", List.of("SUMM", "S136"), List.of("Account Offences and Penalties"));

        assertThat(summOnly.offences().offence().get(0).impositions().imposition().get(0).impositionType())
                .as("negative control: SUMM alone supplies Imposition type")
                .isNotNull();
        assertThat(summOnly.offences().offence().get(0).placeOfOffence())
                .as("negative control: SUMM alone must NOT supply Place of offence")
                .isNull();

        assertThat(s136Only.offences().offence().get(0).placeOfOffence())
                .as("negative control: S136 alone supplies Place of offence")
                .isNotNull();
        assertThat(s136Only.offences().offence().get(0).impositions().imposition().get(0).impositionType())
                .as("negative control: S136 alone must NOT supply Imposition type")
                .isNull();

        assertThat(both.offences().offence().get(0).impositions().imposition().get(0).impositionType())
                .as("union: both codes together supply Imposition type")
                .isNotNull();
        assertThat(both.offences().offence().get(0).placeOfOffence())
                .as("union: both codes together supply Place of offence")
                .isNotNull();
    }

    // Finding M8: this test posted FSN + REM, which have IDENTICAL field lists ("Account No.",
    // "Total Balance" — see result-codes.yaml). A duplicate write of the same value to the same
    // path by two codes is indistinguishable from a genuinely deduplicated union: the assertion
    // would pass with or without deduplication, because both codes resolve to the exact same
    // value regardless. It is deleted rather than "fixed" because there is no way to make it
    // falsifiable under this architecture: `requiredLabels` in NowsDataItemsAssembler.assemble()
    // is a java.util.Set, so duplicate labels across posted codes are deduplicated by ordinary Set
    // semantics before any per-field resolution happens — there is no separate dedup step of the
    // assembler's own to exercise, and a field's resolved value never depends on which code
    // requested it (ValueResolver keys only on caseUrn + FieldPath). `unions_required_fields_
    // across_several_posted_codes` above already covers the real behaviour this test was gesturing
    // at (multiple codes contributing to one entity) with a genuine negative control.

    @Test
    void resolves_gob_and_cp_spellings_of_the_same_code_identically() {
        final NowsDataItems viaDw = assembler.assemble(
                "E999999999", List.of("DW"), List.of("Account Warrant Number"));
        final NowsDataItems viaWc = assembler.assemble(
                "E999999999", List.of("WC"), List.of("Account Warrant Number"));

        assertThat(viaDw.accountWarrantNumber()).isEqualTo(viaWc.accountWarrantNumber());
    }

    @Test
    void emits_nothing_for_field_labels_that_have_no_property_in_the_contract() {
        // S136 requires "Date of Offence", which is unmapped — offences must still be valid.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("S136"), List.of("Account Offences and Penalties"));

        assertThat(items.offences()).isNotNull();
        assertThat(items.offences().offence()).isNotNull();
    }

    @Test
    void returns_defaults_only_for_a_code_with_no_table_row() {
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("NOENF"), List.of("Account Balance"));

        assertThat(items.accountBalance()).isEqualByComparingTo("1250.00");
    }

    // Finding I4: posting an additional fields:[] result code must never shrink an entity's
    // content. ACNOTE has no CIMD-4372 field mapping (fields: []); AEOC contributes exactly one
    // field to "terms" ("Payment Terms" -> terms.english_due). Before the fix, ACNOTE alone
    // triggered defaultFor()'s rich union (all 5 terms fields), while ACNOTE + AEOC together
    // triggered the main union (AEOC's 1 field only) with no baseline gap-fill (terms has no
    // baseline rows), so adding AEOC on top of ACNOTE APPEARED to remove 4 fields. This is the
    // exact scenario the whole-branch review found.
    @Test
    void posting_an_additional_fields_empty_code_never_shrinks_an_entity() {
        final NowsDataItems acnoteAlone = assembler.assemble(
                "E999999999", List.of("ACNOTE"), List.of("Account Terms to Pay"));
        final NowsDataItems acnotePlusAeoc = assembler.assemble(
                "E999999999", List.of("ACNOTE", "AEOC"), List.of("Account Terms to Pay"));

        final int fieldsWithAcnoteAlone = countNonNullTermsFields(acnoteAlone);
        final int fieldsWithBoth = countNonNullTermsFields(acnotePlusAeoc);

        assertThat(fieldsWithBoth)
                .as("posting AEOC on top of ACNOTE must not leave terms with FEWER populated "
                        + "fields (%d) than ACNOTE alone had (%d)", fieldsWithBoth, fieldsWithAcnoteAlone)
                .isGreaterThanOrEqualTo(fieldsWithAcnoteAlone);
        assertThat(acnotePlusAeoc.terms().english_due())
                .as("AEOC's own contribution must still be present")
                .isNotNull();
    }

    private static int countNonNullTermsFields(final NowsDataItems items) {
        final var terms = items.terms();
        int count = 0;
        if (terms.english_due() != null) {
            count++;
        }
        if (terms.english_firstDate() != null) {
            count++;
        }
        if (terms.english_instalment() != null) {
            count++;
        }
        if (terms.english_lumpsum() != null) {
            count++;
        }
        if (terms.english_instalmetPaymentPeriod() != null) {
            count++;
        }
        return count;
    }

    @Test
    void prefers_seeded_values_over_defaults() {
        final NowsDataItems items = assembler.assemble(
                "E011122334", List.of("ABDC"), List.of("Account Number", "Account Balance"));

        assertThat(items.accountNumber()).isEqualTo("ACC9001");
        assertThat(items.accountBalance()).isEqualByComparingTo("340.00");
    }
}
