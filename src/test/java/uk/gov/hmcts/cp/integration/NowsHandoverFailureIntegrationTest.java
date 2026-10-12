package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 002 US2 (T058): a hand-over hearing-nows rejects, fails or does not answer in time is recorded FAILED with the
 * status or cause; the submission stays SUCCEEDED, GOB is called once, and neither GOB data nor PII reaches the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
// short read timeout so the "no answer in time" case is quick; the gateway keeps its own
@TestPropertySource(properties = {"cp.hearing-result.payment-due-date-fallback=HEARING_DATE", "cp.hearing-nows.read-timeout-ms=500"})
class NowsHandoverFailureIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @ParameterizedTest(name = "hearing-nows {0} → {1}")
    @CsvSource({"403, 0, HTTP 403", "500, 0, HTTP 500", "202, 2000, ResourceAccessException"})
    void failed_hand_over_should_be_recorded_and_gob_not_called_again(final int status, final int delayMs, final String detail,
                                                                       final CapturedOutput output) {
        STUBS.stubFor(post(urlEqualTo(HEARING_NOWS_PATH)).willReturn(aResponse().withStatus(status).withFixedDelay(delayMs)));

        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        STUBS.verify(1, postRequestedFor(urlEqualTo(HEARING_NOWS_PATH)));
        final HearingResultSubmissionEntity row = repository.findAll().getFirst();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getNowsHandoverStatus()).isEqualTo(NowsHandoverStatus.FAILED);
        assertThat(row.getNowsHandoverDetail()).isEqualTo(detail);
        assertThat(output.getAll())
                .contains("hand-over to hearing NOWS failed: " + detail)
                .doesNotContain("125.5", "Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street");
    }
}
