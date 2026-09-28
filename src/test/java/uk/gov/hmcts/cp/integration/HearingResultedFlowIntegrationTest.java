package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 end to end: hearing-resulted event → selection → reference-data shortCode lookup (WireMock)
 * → minimum payload → gateway (WireMock) → persisted SUCCEEDED row with GOB's reply, against a real
 * local Postgres. It also asserts no PII reaches the logs (FR-017, constitution Principle IV).
 */
@ExtendWith(OutputCaptureExtension.class)
class HearingResultedFlowIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void enforcement_first_share_should_be_submitted_once_and_reply_stored(final CapturedOutput output) {
        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        final String expectedRequest = Fixtures.json("expected/hearing-resulted-request-minimum.json");
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH))
                .withRequestBody(equalToJson(expectedRequest, true, false)));
        assertThat(LibraContract.hearingResultedRequestViolations(expectedRequest)).isEmpty();

        final List<HearingResultSubmissionEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        final HearingResultSubmissionEntity row = rows.getFirst();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getCaseUrn()).isEqualTo("E012345678");
        assertThat(row.getResponsePayload()).contains("9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c").contains("accountBalance");
        assertThat(row.getRequestPayload()).contains("\"prosecutorDefendantId\"");

        // FR-017: ids, caseUrn and outcome may be logged; defendant PII must not be
        assertThat(output.getAll())
                .contains("E012345678")
                .doesNotContain("Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street", "N17 6RT", "02081234567");
    }

    @Test
    void non_enforcement_case_should_not_be_submitted() {
        processor.process(Fixtures.event("hearing-resulted-non-enforcement.json"));

        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(repository.count()).isZero();
    }

    @Test
    void same_share_delivered_twice_should_be_submitted_once() {
        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));
        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(repository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "hearing-resulted-reshare.json",
        "hearing-resulted-two-defendants.json",
        "hearing-resulted-two-enforcement-cases.json",
        "hearing-resulted-linked-application.json"
    })
    void reshare_and_out_of_scope_shapes_should_not_be_submitted(final String fixture) {
        processor.process(Fixtures.event(fixture));

        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(repository.count()).isZero();
    }

    @Test
    void without_a_nows_mapping_the_request_should_have_no_nows_data_request() {
        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        assertThat(STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH))))
                .singleElement()
                .satisfies(post -> assertThat(post.getBodyAsString()).doesNotContain("nowsDataRequest"));
    }
}
