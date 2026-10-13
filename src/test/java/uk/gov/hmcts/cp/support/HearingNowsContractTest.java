package uk.gov.hmcts.cp.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract copy itself: a valid hand-over passes, and the schema's rules (incl. the nows-data-items reference) apply. */
class HearingNowsContractTest {

    private static final String VALID = """
            {"correlationId":"91f9490e-846c-3c3b-b827-1eac49327338","hearingId":"04180ff1-99b0-40b7-9929-ca05bdc767d8",
             "hearingDay":"2026-05-03","masterDefendantId":"0ddbc4a7-3c85-4bc3-bdd5-843817320b98",
             "defendantId":"0ddbc4a7-3c85-4bc3-bdd5-843817320b98","prosecutionCaseId":"861aaa70-f142-469d-a7db-9af395347dd5",
             "caseUrn":"E012345678","accountNumber":"26000123A","sharedTime":"2026-05-03T14:30:00.000Z","gobDataReused":false,
             "coveredResultDefinitionIds":["11111111-1111-1111-1111-111111111111"],"nowsDataItems":{"accountBalance":125.5}}""";

    @Test
    void valid_hand_over_should_have_no_violations() {
        assertThat(HearingNowsContract.addEnforcementNowsDataViolations(VALID)).isEmpty();
    }

    @Test
    void missing_field_should_be_a_violation() {
        assertThat(HearingNowsContract.addEnforcementNowsDataViolations(VALID.replace("\"caseUrn\":\"E012345678\",", "")))
                .anySatisfy(message -> assertThat(message).contains("caseUrn"));
    }

    @Test
    void unknown_nows_data_item_should_be_a_violation_of_the_referenced_schema() {
        assertThat(HearingNowsContract.addEnforcementNowsDataViolations(VALID.replace("{\"accountBalance\":125.5}",
                "{\"accountBalance\":\"not a number\"}"))).isNotEmpty();
    }
}
