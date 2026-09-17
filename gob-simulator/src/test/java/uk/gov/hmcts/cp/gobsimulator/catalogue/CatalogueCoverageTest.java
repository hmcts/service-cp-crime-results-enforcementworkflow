package uk.gov.hmcts.cp.gobsimulator.catalogue;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the catalogue against drifting away from the v0.3.0 resultCode enum. */
class CatalogueCoverageTest {

    /** Every value of the resultCode enum in libra-gateway-hearing-events-v0.3.0.yml. */
    private static final Set<String> ENUM_CODES = Set.of(
            "ABDC", "AEO", "AEOC", "BWTD", "BWTU", "CLAMPO", "COLLO", "CW", "CWN", "DW",
            "FSN", "MPSO", "NBWT", "NOENF", "REGF", "REM", "S136", "SC", "SUMM", "TFOOUT",
            "WDN", "TFOUT", "WC", "WWDN");

    /** In the CIMD-4372 table but absent from the enum — CP cannot post these. Spec OQ-3. */
    private static final Set<String> NOT_POSTABLE = Set.of(
            "ACF", "AEC", "CLAMPS", "FIDIC", "FIDICT", "FTTP", "LATG", "LATR", "PGPAY", "PTNV");

    private final Catalogue catalogue = new CatalogueLoader().load();

    @Test
    void every_postable_result_code_is_known_to_the_catalogue() {
        for (final String code : ENUM_CODES) {
            assertThat(catalogue.isKnown(code)).as("resultCode %s must be in the catalogue", code).isTrue();
        }
    }

    @Test
    void codes_absent_from_the_enum_are_marked_unpostable() {
        for (final String code : NOT_POSTABLE) {
            assertThat(catalogue.isPostable(code))
                    .as("%s is not in the v0.3.0 enum and must be marked postable: false", code)
                    .isFalse();
        }
    }

    @Test
    void every_field_label_used_by_a_result_code_has_a_field_path_entry() {
        for (final String code : catalogue.allCodes()) {
            for (final String label : catalogue.fieldsFor(code)) {
                assertThat(catalogue.fieldPath(label))
                        .as("code %s references label '%s' with no field-paths entry", code, label)
                        .isNotNull();
            }
        }
    }
}
