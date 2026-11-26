package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.ClientProperties;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

@ExtendWith(OutputCaptureExtension.class)
class ReferenceDataClientTest {

    private static final String BASE_URL = "http://refdata.test";
    private static final String CJSCPPUID = "11111111-2222-3333-4444-555555555555";
    private static final UUID DEFINITION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate ON = LocalDate.parse("2026-05-03");
    private static final String URL = BASE_URL
            + "/referencedata-query-api/query/api/rest/referencedata/result-definitions/" + DEFINITION_ID + "?on=2026-05-03";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReferenceDataClient client = new ReferenceDataClient(builder, properties());

    private static ClientProperties properties() {
        final ClientProperties properties = new ClientProperties();
        properties.getReferenceData().setBaseUrl(BASE_URL);
        properties.getReferenceData().setCjscppuid(CJSCPPUID);
        return properties;
    }

    @Test
    void should_return_short_code_with_vendor_accept_and_cjscppuid_headers() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(GET))
                .andExpect(header("Accept", "application/vnd.referencedata.get-result-definition+json"))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andRespond(withSuccess("{\"id\":\"" + DEFINITION_ID + "\",\"label\":\"Committal warrant\",\"shortCode\":\"WC\",\"rank\":5}",
                        MediaType.valueOf("application/vnd.referencedata.get-result-definition+json")));

        assertThat(client.findShortCode(DEFINITION_ID, ON)).contains("WC");
        server.verify();
    }

    @Test
    void not_found_should_return_empty() {
        server.expect(requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.findShortCode(DEFINITION_ID, ON)).isEmpty();
    }

    @Test
    void repeated_lookup_should_be_served_from_cache() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("{\"shortCode\":\"WC\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findShortCode(DEFINITION_ID, ON)).contains("WC");
        assertThat(client.findShortCode(DEFINITION_ID, ON)).contains("WC");
        server.verify(); // exactly one HTTP call
    }

    @Test
    void server_error_should_throw_reference_data_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findShortCode(DEFINITION_ID, ON)).isInstanceOf(ReferenceDataUnavailableException.class);
    }

    @Test
    void transport_failure_should_throw_reference_data_unavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findShortCode(DEFINITION_ID, ON)).isInstanceOf(ReferenceDataUnavailableException.class);
    }

    @Test
    void not_found_should_not_be_cached() {
        server.expect(times(2), requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.findShortCode(DEFINITION_ID, ON)).isEmpty();
        assertThat(client.findShortCode(DEFINITION_ID, ON)).isEmpty();
        server.verify(); // both calls reached reference data
    }

    // TEMP DIAGNOSTICS: an error response logs the status, url and a stack trace without the response body
    @Test
    void server_error_should_log_status_and_url_without_body(final CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withStatus(SERVICE_UNAVAILABLE).body("defendant Edward Harrison"));

        assertThatThrownBy(() -> client.findShortCode(DEFINITION_ID, ON)).isInstanceOf(ReferenceDataUnavailableException.class);
        assertThat(withoutTempDiagnostics(output.getAll()))
                .contains("HTTP 503 Service Unavailable (url=" + URL)
                .contains("at uk.gov.hmcts.cp.client.ReferenceDataClient")
                .doesNotContain("Edward");
    }

    // TEMP DIAGNOSTICS: a transport failure logs the url, the root cause and a stack trace
    @Test
    void transport_failure_should_log_url_and_root_cause(final CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findShortCode(DEFINITION_ID, ON)).isInstanceOf(ReferenceDataUnavailableException.class);
        assertThat(withoutTempDiagnostics(output.getAll()))
                .contains("url=" + URL)
                .contains("ResourceAccessException")
                .contains("rootCause=java.net.SocketTimeoutException: Read timed out")
                .contains("at uk.gov.hmcts.cp.client.ReferenceDataClient");
    }

    // CIMD-4640: the enforcement area's accounting division code, by postcode (encoded) or by an LJA's national court code
    private static final String ENFORCEMENT_AREA_URL = BASE_URL + "/referencedata-query-api/query/api/rest/referencedata/enforcement-area";
    private static final String BY_POSTCODE_URL = ENFORCEMENT_AREA_URL + "?postcode=XX1%201XX";

    @Test
    void enforcement_area_should_give_its_account_division_code_by_postcode() {
        server.expect(once(), requestTo(BY_POSTCODE_URL))
                .andExpect(method(GET))
                .andExpect(header("Accept", "application/vnd.referencedata.query.enforcement-area+json"))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andRespond(withSuccess("{\"enforcingCourtCode\":2222,\"accountDivisionCode\":26,\"localJusticeArea\":{\"nationalCourtCode\":\"9999\"}}",
                        MediaType.valueOf("application/vnd.referencedata.query.enforcement-area+json")));

        assertThat(client.findAccountDivisionCodeByPostcode("XX1 1XX")).contains(26);
        server.verify();
    }

    @Test
    void enforcement_area_should_give_its_account_division_code_by_lja_code() {
        server.expect(once(), requestTo(ENFORCEMENT_AREA_URL + "?localJusticeAreaNationalCourtCode=9999"))
                .andRespond(withSuccess("{\"accountDivisionCode\":31}", MediaType.APPLICATION_JSON));

        assertThat(client.findAccountDivisionCodeByLjaCode("9999")).contains(31);
    }

    @Test
    void enforcement_area_without_a_division_code_should_give_none() {
        server.expect(requestTo(BY_POSTCODE_URL)).andRespond(withSuccess("{\"enforcingCourtCode\":2222}", MediaType.APPLICATION_JSON));

        assertThat(client.findAccountDivisionCodeByPostcode("XX1 1XX")).isEmpty();
    }

    // no enforcement area (404), or a postcode reference data does not accept (400)
    @Test
    void unknown_or_rejected_postcode_should_give_no_division_code() {
        server.expect(requestTo(BY_POSTCODE_URL)).andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo(BY_POSTCODE_URL)).andRespond(withStatus(BAD_REQUEST));

        assertThat(client.findAccountDivisionCodeByPostcode("XX1 1XX")).isEmpty();
        assertThat(client.findAccountDivisionCodeByPostcode("XX1 1XX")).isEmpty();
    }

    // FR-017: the postcode is in the url, so neither the logs nor the exception carry the url or the failure's message
    @Test
    void failed_enforcement_area_lookup_should_throw_without_the_postcode(final CapturedOutput output) {
        server.expect(requestTo(BY_POSTCODE_URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));
        server.expect(requestTo(BY_POSTCODE_URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findAccountDivisionCodeByPostcode("XX1 1XX"))
                .isInstanceOf(EnforcementAreaUnavailableException.class)
                .hasMessage("Enforcement area lookup by postcode failed: ServiceUnavailable")
                .hasNoCause();
        assertThatThrownBy(() -> client.findAccountDivisionCodeByPostcode("XX1 1XX"))
                .isInstanceOf(EnforcementAreaUnavailableException.class)
                .hasMessage("Enforcement area lookup by postcode failed: ResourceAccessException");
        assertThat(output.getAll()).contains("Enforcement area lookup by postcode failed").doesNotContain("XX1", "enforcement-area?");
    }
}
