package uk.gov.hmcts.cp.integration;

import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.service.ResultNotificationReplyListener;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/**
 * Feature 005 (CIMD-4548) end to end: a share with a trigger shortCode reaches {@code DOC_REQUESTED}, the doc
 * generator's own completion event (simulated on the embedded broker, same as {@code PublicEventLoggingListener}'s
 * single subscription) drives {@code DOC_READY}/{@code DOC_FAILED}, and the gateway's own result event (simulated
 * by calling {@link ResultNotificationReplyListener#handle} directly - research.md R12: no embedded-broker
 * equivalent exists for Azure Service Bus) drives {@code SENT}/{@code EMAIL_FAILED}. Blob and Service Bus SDK
 * clients are faked ({@link ResultNotificationTestFakesConfig}), not real Azure/Azurite.
 */
@ExtendWith(OutputCaptureExtension.class)
class ResultNotificationFlowIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final String FIXTURE = "events/hearing-resulted-result-notification.json";
    private static final String RELATED_REFERENCE_NUMBER = "1230050001A";
    private static final String TRIGGER_SHORT_CODE_DEFINITION_ID = "7b1c2d3e-0000-4000-8000-000000000506";
    private static final String DOC_GENERATOR_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/documents/generate-document";
    private static final String ORIGINATING_SOURCE = "service-cp-crime-results-enforcementworkflow";
    private static final String CPPNAME_DOCUMENT_AVAILABLE = "public.systemdocgenerator.events.document-available";
    private static final String CPPNAME_GENERATION_FAILED = "public.systemdocgenerator.events.generation-failed";

    @Autowired
    private ResultNotificationReplyListener replyListener;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Value("${cp.messaging.public-event-topic}")
    private String publicEventTopic;

    @BeforeEach
    void stubFeature005Dependencies() {
        stubShortCode(TRIGGER_SHORT_CODE_DEFINITION_ID, "CW");
        STUBS.stubFor(get(urlPathEqualTo("/reference-data-query-api/query/api/rest/enforcement-area/internal/123"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"divisionCode\":\"123\",\"teamEmail\":\"enforcement-team@example.gov.uk\"}")));
        STUBS.stubFor(post(urlEqualTo(DOC_GENERATOR_PATH)).willReturn(aResponse().withStatus(202)));
    }

    @Test
    void positive_path_should_reach_sent_and_purge_both_blobs(final CapturedOutput output) {
        final UUID triggerNotificationId = notifyShare(FIXTURE, "result-notif-positive");

        final EnforcementResultNotificationEntity docRequested = awaitStatus(triggerNotificationId, ResultNotificationStatus.DOC_REQUESTED);
        assertThat(docRequested.getTeamEmail()).isEqualTo("enforcement-team@example.gov.uk");
        assertThat(docRequested.getDivisionCode()).isEqualTo("123");

        publishDocGeneratorEvent(CPPNAME_DOCUMENT_AVAILABLE, docRequested.getId(), null);
        awaitStatus(triggerNotificationId, ResultNotificationStatus.EMAIL_REQUESTED);

        replyListener.handle("notification-sent", "{\"notificationId\":\"" + docRequested.getId()
                + "\",\"sentTime\":\"2026-05-03T14:35:00Z\"}");

        final EnforcementResultNotificationEntity sent = awaitStatus(triggerNotificationId, ResultNotificationStatus.SENT);
        assertThat(sent.getPurgedAt()).isNotNull();
        // FR-016: every SENT outcome is logged with caseUrn, Division Code, team email, shortCodes, sharing user, timestamp
        assertThat(withoutTempDiagnostics(output.getAll())).contains(sent.getId().toString())
                .contains("SENT: caseUrn=E005000501", "divisionCode=123", "teamEmail=enforcement-team@example.gov.uk")
                .contains("CW");
    }

    @Test
    void doc_generation_failure_should_end_doc_failed_without_an_email_or_a_purge() {
        final UUID triggerNotificationId = notifyShare(FIXTURE, "result-notif-doc-failed");
        final EnforcementResultNotificationEntity docRequested = awaitStatus(triggerNotificationId, ResultNotificationStatus.DOC_REQUESTED);

        publishDocGeneratorEvent(CPPNAME_GENERATION_FAILED, docRequested.getId(), "TEMPLATE_NOT_FOUND");

        final EnforcementResultNotificationEntity failed = awaitStatus(triggerNotificationId, ResultNotificationStatus.DOC_FAILED);
        assertThat(failed.getLastError()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(failed.getPurgedAt()).isNull();
    }

    @Test
    void email_send_failure_after_acceptance_should_end_email_failed_without_a_purge() {
        final UUID triggerNotificationId = notifyShare(FIXTURE, "result-notif-email-failed");
        final EnforcementResultNotificationEntity docRequested = awaitStatus(triggerNotificationId, ResultNotificationStatus.DOC_REQUESTED);
        publishDocGeneratorEvent(CPPNAME_DOCUMENT_AVAILABLE, docRequested.getId(), null);
        awaitStatus(triggerNotificationId, ResultNotificationStatus.EMAIL_REQUESTED);

        replyListener.handle("notification-failed", "{\"notificationId\":\"" + docRequested.getId()
                + "\",\"failedTime\":\"2026-05-03T14:35:00Z\",\"errorMessage\":\"TEMPLATE_RETIRED\"}");

        final EnforcementResultNotificationEntity failed = awaitStatus(triggerNotificationId, ResultNotificationStatus.EMAIL_FAILED);
        assertThat(failed.getLastError()).isEqualTo("TEMPLATE_RETIRED");
        assertThat(failed.getPurgedAt()).isNull();
    }

    // the gateway's own schema validation rejects the command outright, before ever attempting to send - no
    // notificationId of its own; correlates via its echoed originalEventPayload (research.md R1)
    @Test
    void email_validation_rejection_should_end_email_failed_correlated_via_the_original_payload() {
        final UUID triggerNotificationId = notifyShare(FIXTURE, "result-notif-validation-failed");
        final EnforcementResultNotificationEntity docRequested = awaitStatus(triggerNotificationId, ResultNotificationStatus.DOC_REQUESTED);
        publishDocGeneratorEvent(CPPNAME_DOCUMENT_AVAILABLE, docRequested.getId(), null);
        awaitStatus(triggerNotificationId, ResultNotificationStatus.EMAIL_REQUESTED);

        final String originalCommand = "{\"notificationId\":\"" + docRequested.getId()
                + "\",\"templateId\":\"t\",\"sendToAddress\":\"bad\"}";
        final String escapedOriginalCommand = originalCommand.replace("\"", "\\\"");
        replyListener.handle("notification-validation-failed", "{\"reason\":\"PAYLOAD_VALIDATION_FAILED\","
                + "\"details\":\"sendToAddress: must be a valid email\",\"originalEventPayload\":\"" + escapedOriginalCommand + "\"}");

        final EnforcementResultNotificationEntity failed = awaitStatus(triggerNotificationId, ResultNotificationStatus.EMAIL_FAILED);
        assertThat(failed.getLastError()).isEqualTo("PAYLOAD_VALIDATION_FAILED: sendToAddress: must be a valid email");
        assertThat(failed.getPurgedAt()).isNull();
    }

    private void publishDocGeneratorEvent(final String cppname, final UUID sourceCorrelationId, final String reason) {
        final StringBuilder body = new StringBuilder("{\"sourceCorrelationId\":\"").append(sourceCorrelationId)
                .append("\",\"originatingSource\":\"").append(ORIGINATING_SOURCE).append('"');
        if (reason != null) {
            body.append(",\"reason\":\"").append(reason).append('"');
        }
        body.append('}');
        jmsTemplate.send(publicEventTopic, session -> {
            final TextMessage message = session.createTextMessage(body.toString());
            message.setStringProperty("CPPNAME", cppname);
            return message;
        });
    }

    private EnforcementResultNotificationEntity awaitStatus(final UUID triggerNotificationId, final ResultNotificationStatus status) {
        await().atMost(PROCESSING_TIMEOUT).until(() -> rowFor(triggerNotificationId).filter(row -> row.getStatus() == status).isPresent());
        return rowFor(triggerNotificationId).orElseThrow();
    }

    private java.util.Optional<EnforcementResultNotificationEntity> rowFor(final UUID triggerNotificationId) {
        return resultNotificationRepository.findByTriggerNotificationIdAndRelatedReferenceNumber(triggerNotificationId,
                RELATED_REFERENCE_NUMBER);
    }
}
