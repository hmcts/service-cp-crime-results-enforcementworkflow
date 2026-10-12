package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
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

import java.util.Comparator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    // T057: a failed hand-over does not stop the next share from being processed (it is sent to GOB as usual)
    @Test
    void next_share_should_be_processed_after_a_failed_hand_over() {
        STUBS.stubFor(post(urlEqualTo(HEARING_NOWS_PATH)).willReturn(aResponse().withStatus(500)));
        // results are looked up on their ordered date: offence 2's final result (day 2) and its day-1 adjournment
        stubShortCode("22222222-2222-2222-2222-222222222222", "WC", "2026-05-10");
        stubShortCode("44444444-4444-4444-4444-444444444444", "NEXH");

        processor.process(Fixtures.event("hearing-resulted-day1-offence1-only.json"));
        processor.process(Fixtures.event("hearing-resulted-day2-offence2-resulted.json"));

        STUBS.verify(2, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        STUBS.verify(2, postRequestedFor(urlEqualTo(HEARING_NOWS_PATH)));
        assertThat(repository.findAll().stream().sorted(Comparator.comparing(HearingResultSubmissionEntity::getCreatedAt)).toList())
                .extracting(HearingResultSubmissionEntity::getStatus, HearingResultSubmissionEntity::getNowsHandoverStatus)
                .containsExactly(tuple(SubmissionStatus.SUCCEEDED, NowsHandoverStatus.FAILED),
                        tuple(SubmissionStatus.SUCCEEDED, NowsHandoverStatus.FAILED));
    }
}
