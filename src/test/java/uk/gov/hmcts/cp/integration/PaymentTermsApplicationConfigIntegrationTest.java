package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.entity.SubmissionStatus;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 004 (CIMD-4251) with the payment terms configuration of {@code application.yaml} (OQ-1 resolved: CP prompt
 * references and the value formats hearing publishes), i.e. no {@code paymentterms-test} profile. The prompts in the
 * fixtures are shaped as in {@code public.events.hearing.hearing-resulted}: {@code £} amounts, {@code dd/MM/yyyy} dates,
 * the PF fixed list values and both boolean forms ({@code true}/{@code false}, {@code Yes}/{@code No}). Values are
 * synthetic.
 */
class PaymentTermsApplicationConfigIntegrationTest extends WorkflowStubsIntegrationTestBase {

    // one definition id per fixture: the reference data client caches short codes for the whole Spring context
    private static final String INSTL_RESULT = "99999999-9999-9999-9999-999999999999";
    private static final String LUMSI_RESULT = "99999999-9999-9999-9999-0000000000a1";
    private static final String PDATE_RESULT = "99999999-9999-9999-9999-0000000000a2";
    private static final String HEARING_LEVEL_INSTL_RESULT = "99999999-9999-9999-9999-0000000000a3";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void no_payment_terms_result_should_be_sent_without_payment_terms() {
        process("hearing-resulted-enforcement.json");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
            assertThat(row.getRequestPayload()).doesNotContain("paymentTerms");
        });
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void lumsi_with_cp_prompts_should_be_sent_with_its_block() {
        stubShortCode(LUMSI_RESULT, "LUMSI");

        process("hearing-resulted-enforcement-payment-terms-cp-lumsi.json");

        assertSentWith("""
                {"instalmentFrequency":"M","paymentDueDate":"2031-04-11","instalmentAmount":73.61,"lumpSum":2500.37,\
                "paymentCardRequested":"Y","parentToPay":"N"}""");
    }

    @Test
    void pdate_with_cp_prompts_should_be_sent_with_its_due_date() {
        stubShortCode(PDATE_RESULT, "PDATE");

        process("hearing-resulted-enforcement-payment-terms-cp-pdate.json");

        assertSentWith("""
                {"paymentDueDate":"2031-05-16","paymentCardRequested":"N","parentToPay":"Y"}""");
    }

    /** Where CP puts payment terms results in a case share: hearing.defendantJudicialResults, by masterDefendantId. */
    @Test
    void hearing_level_instl_with_cp_prompts_should_be_sent_with_its_block() {
        stubShortCode(HEARING_LEVEL_INSTL_RESULT, "INSTL");

        process("hearing-resulted-enforcement-payment-terms-cp-instl-hearing-level.json");

        assertSentWith("""
                {"instalmentFrequency":"F","paymentDueDate":"2031-03-07","instalmentAmount":487.13,\
                "paymentCardRequested":"N","parentToPay":"N"}""");
    }

    @Test
    void instl_without_its_prompts_should_be_mapping_failed_and_not_sent() {
        stubShortCode(INSTL_RESULT, "INSTL");

        process("hearing-resulted-enforcement-payment-terms.json");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.MAPPING_FAILED);
            assertThat(row.getErrorDetail()).isEqualTo("PAYMENT_TERMS_INCOMPLETE: INSTL instalment-amount missing");
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    private void assertSentWith(final String expectedBlock) {
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).as(row::getErrorDetail).isEqualTo(SubmissionStatus.SUCCEEDED);
            assertThat(JSON.readTree(row.getRequestPayload()).get("paymentTerms")).isEqualTo(JSON.readTree(expectedBlock));
        });
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }
}
