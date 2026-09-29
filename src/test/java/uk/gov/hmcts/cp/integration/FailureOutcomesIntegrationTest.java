package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US4: every attempt ends in a recorded outcome with a reason, with no retries (quickstart scenarios
 * 6-8, 10, 11). The gateway read timeout is shortened to 500 ms so the timeout case runs quickly.
 */
@TestPropertySource(properties = "cp.enforcement-gateway.read-timeout-ms=500")
class FailureOutcomesIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void gateway_502_should_be_recorded_as_failed_with_status() {
        stubGatewayStatus(502, "{\"error\":\"LIBRA_CALL_FAILED\",\"details\":{\"libraStatus\":404,\"errorCode\":\"E404\"}}");

        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
            assertThat(row.getHttpStatus()).isEqualTo(502);
            assertThat(row.getErrorDetail()).contains("E404");
            assertThat(row.getRequestPayload()).isNotNull();
        });
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH))); // no retry
    }

    @Test
    void gateway_slower_than_read_timeout_should_be_failed_with_unknown_outcome_and_not_resent() {
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                .withHeader("Content-Type", "application/json").withBody(GATEWAY_RESPONSE)));
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");

        processor.process(event);
        processor.process(event); // redelivery

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
            assertThat(row.getHttpStatus()).isNull();
            assertThat(row.getErrorDetail()).isEqualTo("TIMEOUT – outcome at GOB unknown");
        });
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void missing_account_number_should_be_recorded_as_mapping_failed() {
        processor.process(Fixtures.event("hearing-resulted-missing-account-number.json"));

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.MAPPING_FAILED);
            assertThat(row.getErrorDetail()).startsWith("PROSECUTOR_DEFENDANT_ID_MISSING");
            assertThat(row.getRequestPayload()).isNull();
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void unknown_codes_only_should_be_recorded_as_skipped_no_result_code() {
        processor.process(Fixtures.event("hearing-resulted-unknown-codes-only.json"));

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SKIPPED_NO_RESULT_CODE);
            assertThat(row.getErrorDetail()).contains("unknown-result-definition:33333333-3333-3333-3333-333333333333");
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void stale_sending_row_then_redelivery_should_be_marked_interrupted_and_not_resent() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final HearingResultedEvent.ProsecutionCase pc = event.hearing().prosecutionCases().getFirst();
        insertSending(event.hearing().id(), pc.id(), pc.defendants().getFirst().id(), Instant.now().minus(Duration.ofMinutes(10)));

        processor.process(event);

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
            assertThat(row.getErrorDetail()).isEqualTo("INTERRUPTED – outcome at GOB unknown");
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void reference_data_unavailable_should_be_recorded_as_not_sent() {
        STUBS.resetMappings();
        stubGateway(GATEWAY_RESPONSE);
        STUBS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/referencedata-query-api/.*"))
                .willReturn(aResponse().withStatus(503)));

        // result type 3333… is never cached (it isn't found in reference data), so the lookup really hits the 503
        processor.process(Fixtures.event("hearing-resulted-unknown-codes-only.json"));

        assertThat(onlyRow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
            assertThat(row.getErrorDetail()).startsWith("NOT_SENT – reference data unavailable");
            assertThat(row.getRequestPayload()).isNull();
        });
        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
    }

    @Test
    void a_failure_should_not_stop_the_next_valid_event() {
        processor.process(Fixtures.event("hearing-resulted-missing-account-number.json"));
        repository.deleteAll(); // same hearing/case/defendant ids: clear so the valid event is a first share

        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        assertThat(onlyRow().getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
    }

    private HearingResultSubmissionEntity onlyRow() {
        assertThat(repository.count()).isEqualTo(1);
        return repository.findAll().getFirst();
    }

    private void insertSending(final UUID hearingId, final UUID caseId, final UUID defendantId, final Instant updatedAt) {
        final HearingResultSubmissionEntity row = new HearingResultSubmissionEntity();
        row.setId(UUID.randomUUID());
        row.setHearingId(hearingId);
        row.setCaseId(caseId);
        row.setDefendantId(defendantId);
        row.setCaseUrn("E012345678");
        row.setSharedTime(Instant.parse("2026-05-03T14:30:00Z"));
        row.setStatus(SubmissionStatus.SENDING);
        row.setRequestPayload("{}");
        row.setCreatedAt(updatedAt);
        row.setUpdatedAt(updatedAt);
        repository.saveAndFlush(row);
    }
}
