package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** The application default (research.md R13): without a payment terms result, nothing is sent and the submission is unsendable. */
@TestPropertySource(properties = "cp.hearing-result.payment-due-date-fallback=NONE")
class PaymentDueDateNoneIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void no_payment_result_with_fallback_none_should_be_mapping_failed_and_not_sent() {
        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.MAPPING_FAILED);
            assertThat(row.getErrorDetail()).startsWith("PAYMENT_DUE_DATE_UNAVAILABLE");
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }
}
