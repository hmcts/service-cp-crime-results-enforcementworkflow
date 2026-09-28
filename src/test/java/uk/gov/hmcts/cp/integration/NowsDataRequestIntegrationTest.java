package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.Fixtures;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** US3 with a shortCode → NOWS data item mapping configured (application-nowsmapping-test.yaml). */
@ActiveProfiles("nowsmapping-test")
class NowsDataRequestIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void mapped_short_codes_should_request_the_unique_union_of_items() {
        processor.process(Fixtures.event("hearing-resulted-enforcement.json")); // SC + WC

        final List<LoggedRequest> posts = STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(posts).hasSize(1);
        final String body = posts.getFirst().getBodyAsString();
        final JsonNode items = PayloadJson.MAPPER.readTree(body).path("nowsDataRequest").path("nowsDataItems");
        assertThat(items.valueStream().map(item -> item.path("name").asString()).toList())
                .containsExactly("Account Balance", "Account Number", "Account Warrant Number");
        assertThat(LibraContract.hearingResultedRequestViolations(body)).isEmpty();
    }

    @Test
    void empty_nows_data_items_in_the_reply_should_be_stored_as_succeeded() {
        stubGateway("{\"caseUrn\":\"E012345678\",\"timestamp\":\"2026-05-03T14:30:00Z\",\"nowsDataItems\":{}}");

        processor.process(Fixtures.event("hearing-resulted-enforcement.json"));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
            assertThat(row.getResponsePayload()).contains("\"nowsDataItems\"");
        });
    }
}
