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
        // Both codes write into the same "offences" entity, so whichever is posted alone still
        // populates that entity — the AC2 default-fill in assemble() only fires when an entity is
        // entirely untouched, so it cannot paper over a missing sub-field here (unlike a bare
        // top-level scalar, where the default-fill would mask a dropped contribution). ABDC alone
        // supplies Balance Outstanding (accountTotal) but not Amount Paid or Cancelled
        // (accountPaid); CW alone supplies accountPaid but not accountTotal. Only the union of
        // both codes leaves neither sub-field null.
        final NowsDataItems items = assembler.assemble(
                "E999999999", List.of("ABDC", "CW"), List.of("Account Offences and Penalties"));

        assertThat(items.offences().accountTotal()).isNotNull();
        assertThat(items.offences().accountPaid()).isNotNull();
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
