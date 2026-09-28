package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogueLoaderTest {

    private final Catalogue catalogue = new CatalogueLoader().load();

    @Test
    void maps_request_names_that_do_not_camel_case_to_their_real_property() {
        assertThat(catalogue.propertiesFor("Defendant Account")).containsExactly("defendant");
        assertThat(catalogue.propertiesFor("Account Offences and Penalties")).containsExactly("offences");
        assertThat(catalogue.propertiesFor("Account Terms to Pay")).containsExactly("terms");
        assertThat(catalogue.propertiesFor("CT Account Bank Details")).containsExactly("ctBankDetails");
        assertThat(catalogue.propertiesFor("Days Before Release of Warrant"))
                .containsExactly("daysBeforeReleaseWarrant");
    }

    @Test
    void maps_account_history_to_both_history_properties() {
        assertThat(catalogue.propertiesFor("Account History"))
                .containsExactlyInAnyOrder("paymentHistory", "transactionHistory");
    }

    @Test
    void resolves_gob_to_cp_result_code_aliases() {
        assertThat(catalogue.fieldsFor("DW")).isEqualTo(catalogue.fieldsFor("WC"));
        assertThat(catalogue.fieldsFor("TFOUT")).isEqualTo(catalogue.fieldsFor("TFOOUT"));
        assertThat(catalogue.fieldsFor("WWDN")).isEqualTo(catalogue.fieldsFor("WDN"));
    }

    @Test
    void returns_no_fields_for_codes_with_no_table_row() {
        assertThat(catalogue.fieldsFor("NOENF")).isEmpty();
        assertThat(catalogue.fieldsFor("WDN")).isEmpty();
        assertThat(catalogue.fieldsFor("WWDN")).isEmpty();
    }

    @Test
    void exposes_the_root_property_of_a_nested_path() {
        assertThat(catalogue.fieldPath("Balance Outstanding").rootProperty()).isEqualTo("offences");
        assertThat(catalogue.fieldPath("Payment Terms").rootProperty()).isEqualTo("terms");
        assertThat(catalogue.fieldPath("Total Balance").rootProperty()).isEqualTo("accountBalance");
    }

    @Test
    void flags_labels_that_have_no_property_in_the_contract() {
        final List<String> unmapped =
                List.of("Date of Offence", "Start time of offence", "End time of offence",
                        "Reserve Terms", "Reason for decision", "[Directions]");
        for (final String label : unmapped) {
            assertThat(catalogue.fieldPath(label).unmapped())
                    .as("label %s must be marked unmapped", label).isTrue();
            assertThat(catalogue.fieldPath(label).reason())
                    .as("label %s must carry a reason", label).isNotBlank();
        }
    }

    @Test
    void properties_for_returns_an_immutable_list() {
        final List<String> properties = catalogue.propertiesFor("Defendant Account");
        assertThatThrownBy(() -> properties.add("somethingElse"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Finding I3: field-paths.yaml has two rows that deliberately target the same schema path
    // (warrantContactDetails.warrantContactDetailsLine1) — see the comment above them in that
    // file. Map.copyOf(...) (the previous implementation) returns a map whose iteration order is
    // randomised per JVM, so which of the two a last-write-wins consumer sees last would flip
    // between restarts. CatalogueLoader now wraps the already file-ordered LinkedHashMap instead,
    // so fieldPaths() iterates in file order deterministically, every time — pinned here so a
    // regression back to Map.copyOf(), or a reordering of the two rows in field-paths.yaml, is
    // caught rather than silently reintroducing non-determinism.
    @Test
    void preserves_catalogue_file_order_deterministically_for_a_path_written_by_two_labels() {
        final List<String> labelsTargetingLine1 = catalogue.fieldPaths().values().stream()
                .filter(fieldPath -> !fieldPath.unmapped())
                .filter(fieldPath -> "warrantContactDetails.warrantContactDetailsLine1".equals(fieldPath.path()))
                .map(FieldPath::label)
                .toList();

        assertThat(labelsTargetingLine1)
                .as("file order must be preserved, with \"Clamping Contractor name\" placed last "
                        + "(deliberately, per the comment in field-paths.yaml)")
                .containsExactly("Process Server Name", "Clamping Contractor name");
    }
}
