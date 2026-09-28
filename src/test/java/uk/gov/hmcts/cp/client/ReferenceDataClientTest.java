package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
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

class ReferenceDataClientTest {

    private static final String BASE_URL = "http://refdata.test";
    private static final String CJSCPPUID = "11111111-2222-3333-4444-555555555555";
    private static final UUID DEFINITION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate ON = LocalDate.parse("2026-05-03");
    private static final String URL = BASE_URL
            + "/referencedata-query-api/query/api/rest/referencedata/result-definitions/" + DEFINITION_ID + "?on=2026-05-03";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReferenceDataClient client = new ReferenceDataClient(builder, BASE_URL, CJSCPPUID);

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
}
