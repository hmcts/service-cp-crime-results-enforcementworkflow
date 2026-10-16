package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/**
 * Stage 1 end to end (features 001, 003): a share's results → selection → reference-data shortCode lookup (WireMock)
 * → minimum payload → gateway (WireMock) → persisted SUCCEEDED row with GOB's reply, keyed by the notification, and the
 * notification ready to publish GOB's data, against a real local Postgres. It also asserts no PII reaches the logs
 * (FR-017, constitution Principle IV).
 */
@ExtendWith(OutputCaptureExtension.class)
class HearingResultedFlowIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void enforcement_first_share_should_be_submitted_once_and_reply_stored(final CapturedOutput output) {
        final UUID notificationId = process("hearing-resulted-enforcement.json");

        final String expectedRequest = Fixtures.json("expected/hearing-resulted-request-minimum.json");
        STUBS.verify(1, postRequestedFor(urlEqualTo(GATEWAY_PATH))
                .withRequestBody(equalToJson(expectedRequest, true, false)));
        assertThat(LibraContract.hearingResultedRequestViolations(expectedRequest)).isEmpty();

        final List<HearingResultSubmissionEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        final HearingResultSubmissionEntity row = rows.getFirst();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getCorrelationId()).isEqualTo(notificationId);
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getCaseUrn()).isEqualTo("E012345678");
        assertThat(row.getResponsePayload()).contains("9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c").contains("accountBalance");
        assertThat(row.getRequestPayload()).contains("\"prosecutorDefendantId\"");

        // feature 003 FR-012: GOB's data ready for the event, from this submission, for the selected master defendant
        assertThat(notification(notificationId)).satisfies(notification -> {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(notification.getSubmissionId()).isEqualTo(row.getId());
            assertThat(notification.getMasterDefendantId()).isEqualTo(FIXTURE_MASTER_DEFENDANT_ID);
            assertThat(notification.getGobDataReused()).isFalse();
        });

        // FR-017: ids, caseUrn and outcome may be logged; defendant PII must not be
        assertThat(withoutTempDiagnostics(output.getAll()))
                .contains("E012345678")
                .doesNotContain("Edward", "Harrison", "2002-01-10", "QQ123456C", "1 High Street", "ZZ99 9ZZ", "02079460000");
    }

    @Test
    void non_enforcement_case_should_not_be_submitted() {
        final UUID notificationId = process("hearing-resulted-non-enforcement.json");

        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(repository.count()).isZero();
        assertThat(notification(notificationId).getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    void same_notification_processed_twice_should_be_submitted_once() {
        final HearingResultedEvent event = Fixtures.event("hearing-resulted-enforcement.json");
        final UUID notificationId = newNotification(event.hearing().id(), event.hearingDay());

        processor.process(event, notificationId);
        processor.process(event, notificationId);

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
        process(fixture);

        STUBS.verify(0, postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(repository.count()).isZero();
    }

    @Test
    void without_a_nows_mapping_the_request_should_have_no_nows_data_request() {
        process("hearing-resulted-enforcement.json");

        assertThat(STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH))))
                .singleElement()
                .satisfies(post -> assertThat(post.getBodyAsString()).doesNotContain("nowsDataRequest"));
    }
}
