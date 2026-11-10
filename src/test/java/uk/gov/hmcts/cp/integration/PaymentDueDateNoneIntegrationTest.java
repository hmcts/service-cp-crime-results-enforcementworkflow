package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.entity.SubmissionStatus;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interim fix (CIMD-4251): with the application default ({@code payment-due-date-fallback=NONE}), a result with no
 * payment-terms result captured has no real due date to misreport, so the hearing-day placeholder is sent and the
 * submission succeeds. Only a real payment-terms result (INSTL/LUMSI/PDATE/PTFOR) without a mapped source remains
 * unsendable, pending CIMD-4251's real per-shortCode mapping (research.md R13).
 */
@TestPropertySource(properties = "cp.hearing-result.payment-due-date-fallback=NONE")
class PaymentDueDateNoneIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void no_payment_result_with_fallback_none_should_be_mapped_and_sent() {
        process("hearing-resulted-enforcement.json");

        assertThat(repository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED));
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void payment_terms_result_with_fallback_none_should_still_be_mapping_failed_and_not_sent() {
        stubShortCode("99999999-9999-9999-9999-999999999999", "INSTL");

        process("hearing-resulted-enforcement-payment-terms.json");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.MAPPING_FAILED);
            assertThat(row.getErrorDetail()).startsWith("PAYMENT_DUE_DATE_UNAVAILABLE");
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }
}
