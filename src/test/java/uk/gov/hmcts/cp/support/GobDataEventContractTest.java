package uk.gov.hmcts.cp.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event contract copy itself: a valid GOB data event passes, and the schema's rules (incl. the nows-data-items
 * reference) apply, so the payload checks elsewhere cannot pass because the schema failed to load or to resolve.
 */
class GobDataEventContractTest {

    private static final String VALID = """
            {"_metadata":{"id":"3b0c2f0e-0000-4000-8000-0000000000e1","name":"public.enforcementworkflow.enforcement-nows-data-ready"},
             "hearingId":"04180ff1-99b0-40b7-9929-ca05bdc767d8",
             "nows":[{"requestId":"1d2c3b4a-0000-4000-8000-0000000000a1","masterDefendantId":"5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70"}],
             "accountNumber":"26000123A","gobDataReused":false,"sharedTime":"2026-05-03T14:30:00.000Z",
             "nowsDataItems":{"accountBalance":125.5}}""";

    @Test
    void valid_event_should_have_no_violations() {
        assertThat(GobDataEventContract.violations(VALID)).isEmpty();
    }

    @Test
    void missing_account_number_should_be_a_violation() {
        assertThat(GobDataEventContract.violations(VALID.replace("\"accountNumber\":\"26000123A\",", "")))
                .anySatisfy(message -> assertThat(message).contains("accountNumber"));
    }

    @Test
    void unknown_property_should_be_a_violation() {
        assertThat(GobDataEventContract.violations(VALID.replace("\"gobDataReused\":false,", "\"gobDataReused\":false,\"caseUrn\":\"x\",")))
                .anySatisfy(message -> assertThat(message).contains("caseUrn"));
    }

    @Test
    void empty_nows_should_be_a_violation() {
        assertThat(GobDataEventContract.violations(VALID.replaceAll("\"nows\":\\[.*?]", "\"nows\":[]"))).isNotEmpty();
    }

    @Test
    void invalid_nows_data_item_should_be_a_violation_of_the_referenced_schema() {
        assertThat(GobDataEventContract.violations(VALID.replace("{\"accountBalance\":125.5}", "{\"accountBalance\":\"not a number\"}")))
                .isNotEmpty();
    }
}
