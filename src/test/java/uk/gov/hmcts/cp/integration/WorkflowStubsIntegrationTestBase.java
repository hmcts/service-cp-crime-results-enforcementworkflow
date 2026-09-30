package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;
import uk.gov.hmcts.cp.service.HearingResultedProcessor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Real Postgres, with WireMock standing in for reference data and the enforcement gateway. The
 * enforcement fixture's results resolve to SC and WC.
 */
// local/test stand-in for paymentDueDate (research.md R13); the application default is NONE. Subclasses may override.
@TestPropertySource(properties = "cp.hearing-result.payment-due-date-fallback=HEARING_DATE")
public abstract class WorkflowStubsIntegrationTestBase extends IntegrationTestBase {

    protected static final WireMockServer STUBS = new WireMockServer(wireMockConfig().dynamicPort());
    protected static final String GATEWAY_PATH = "/hearingResulted";
    protected static final String GATEWAY_RESPONSE = """
            {"caseUrn":"E012345678","timestamp":"2026-05-03T14:30:00Z","correlationId":"9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c",\
            "nowsDataItems":{"accountBalance":125.5}}""";
    protected static final String DEFINITIONS = "/referencedata-query-api/query/api/rest/referencedata/result-definitions/";

    static {
        STUBS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(STUBS::stop));
    }

    @Autowired
    protected HearingResultedProcessor processor;

    @Autowired
    protected HearingResultSubmissionRepository repository;

    @DynamicPropertySource
    static void stubProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.reference-data.base-url", STUBS::baseUrl);
        registry.add("cp.enforcement-gateway.base-url", STUBS::baseUrl);
    }

    @BeforeEach
    void stubReferenceDataAndGateway() {
        // also cleared before each test: a run stopped mid-test (IDE stop, killed JVM) skips @AfterEach, and the
        // fixtures share hearing/case/defendant ids, so a leftover row would make the next run "already submitted"
        repository.deleteAll();
        stubShortCode("11111111-1111-1111-1111-111111111111", "SC");
        stubShortCode("22222222-2222-2222-2222-222222222222", "WC");
        stubGateway(GATEWAY_RESPONSE);
    }

    @AfterEach
    void resetStubsAndRows() {
        STUBS.resetAll();
        repository.deleteAll();
    }

    protected static void stubGatewayStatus(final int status, final String responseBody) {
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    protected static void stubGateway(final String responseBody) {
        STUBS.stubFor(post(urlEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    private static void stubShortCode(final String definitionId, final String shortCode) {
        STUBS.stubFor(get(urlPathEqualTo(DEFINITIONS + definitionId))
                .withQueryParam("on", equalTo("2026-05-03"))
                .withHeader("Accept", equalTo("application/vnd.referencedata.get-result-definition+json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.referencedata.get-result-definition+json")
                        .withBody("{\"id\":\"" + definitionId + "\",\"shortCode\":\"" + shortCode + "\"}")));
    }
}
