package uk.gov.hmcts.cp.gobsimulator.assembly;

import java.util.List;

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

    @Test
    void deduplicates_a_field_required_by_more_than_one_code() {
        final NowsDataItems both = assembler.assemble(
                "E999999999", List.of("FSN", "REM"), List.of("Account Balance"));
        final NowsDataItems one = assembler.assemble(
                "E999999999", List.of("FSN"), List.of("Account Balance"));

        assertThat(both.accountBalance()).isEqualByComparingTo(one.accountBalance());
    }

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

    @Test
    void prefers_seeded_values_over_defaults() {
        final NowsDataItems items = assembler.assemble(
                "E011122334", List.of("ABDC"), List.of("Account Number", "Account Balance"));

        assertThat(items.accountNumber()).isEqualTo("ACC9001");
        assertThat(items.accountBalance()).isEqualByComparingTo("340.00");
    }
}
