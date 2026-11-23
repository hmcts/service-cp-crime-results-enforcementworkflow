package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.support.LibraContract;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 with the shortCode → NOWS data item mapping of {@code application.yaml} (CIMD-4634: the CIMD-4372 "Result vs
 * Required Field" table): the request carries the unique union of the configured items for the resulted CP shortCodes,
 * and codes the table has no row for request nothing.
 */
class NowsDataRequestIntegrationTest extends WorkflowStubsIntegrationTestBase {

    @Test
    void mapped_short_codes_should_request_the_unique_union_of_items() {
        process("hearing-resulted-enforcement.json"); // SC + WC (sent as DW)

        final String body = singlePost().getBodyAsString();
        final JsonNode items = PayloadJson.MAPPER.readTree(body).path("nowsDataRequest").path("nowsDataItems");
        assertThat(items.valueStream().map(item -> item.path("name").asString()).toList()).containsExactly(
                // SC
                "Account Number", "Account Balance", "Account Offences and Penalties", "Account Date Imposed",
                "Account Terms to Pay",
                // WC (the table's DW row), without the items SC already asked for
                "Account Warrant Number", "Warrant Contact Details");
        assertThat(LibraContract.hearingResultedRequestViolations(body)).isEmpty();
    }

    @Test
    void codes_without_a_table_row_should_send_no_nows_data_request() {
        processWithFreshDefinitionIds("hearing-resulted-enforcement-unmapped-nows-codes.json"); // NOENF + WWDN

        final String body = singlePost().getBodyAsString();
        assertThat(body).doesNotContain("nowsDataRequest").contains("\"NOENF\"").contains("\"WDN\"");
        assertThat(LibraContract.hearingResultedRequestViolations(body)).isEmpty();
    }

    @Test
    void empty_nows_data_items_in_the_reply_should_be_stored_as_succeeded() {
        stubGateway("{\"caseUrn\":\"E012345678\",\"timestamp\":\"2026-05-03T14:30:00Z\",\"nowsDataItems\":{}}");

        process("hearing-resulted-enforcement.json");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
            assertThat(row.getResponsePayload()).contains("\"nowsDataItems\"");
        });
    }

    private static LoggedRequest singlePost() {
        final List<LoggedRequest> posts = STUBS.findAll(postRequestedFor(urlEqualTo(GATEWAY_PATH)));
        assertThat(posts).hasSize(1);
        return posts.getFirst();
    }
}
