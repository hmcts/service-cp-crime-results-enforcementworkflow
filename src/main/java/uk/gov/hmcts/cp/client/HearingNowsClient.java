package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hands GOB's data to hearing-nows' enforcement command {@code hearingnows.add-enforcement-nows-data}
 * (feature 002, contracts/hearingnows-add-enforcement-nows-data.md) as a "System Users" member ({@code CJSCPPUID}).
 * Never throws: 202 is {@link HandoverResult.HandedOver}; any other status, or no response, is
 * {@link HandoverResult.Failed} with the status or the cause class. Never retried, and never a reason to send to GOB
 * again (FR-004). The payload (GOB data, account number) is never logged.
 */
@Slf4j
@Component
public class HearingNowsClient {

    /* default */ static final String PATH = "/hearingnows-command-api/command/api/rest/hearingnows/enforcement-nows-data";
    /* default */ static final MediaType CONTENT_TYPE = MediaType.valueOf("application/vnd.hearingnows.add-enforcement-nows-data+json");
    private static final int ACCEPTED = 202;
    /* default */ static final String PLACEHOLDER_CJSCPPUID = "00000000-0000-0000-0000-000000000000";

    private final RestClient restClient;
    private final String cjscppuid;

    public HearingNowsClient(@Qualifier("hearingNowsRestClientBuilder") final RestClient.Builder restClientBuilder,
                             @Value("${cp.hearing-nows.base-url}") final String baseUrl,
                             @Value("${cp.hearing-nows.cjscppuid}") final String cjscppuid) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.cjscppuid = cjscppuid;
        if (PLACEHOLDER_CJSCPPUID.equals(cjscppuid)) {
            // hearing-nows rejects a non-member (403), so every hand-over would fail (OQ-5)
            log.warn("HEARING_NOWS_CJSCPPUID is the placeholder; hand-overs to hearing-nows will be rejected until a System Users member is set");
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the contract of this client is "never throws"
    public HandoverResult handOver(final String payloadJson) {
        HandoverResult result;
        try {
            final ResponseEntity<Void> response = restClient.post()
                    .uri(PATH)
                    .contentType(CONTENT_TYPE)
                    .header("CJSCPPUID", cjscppuid)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            final int status = response.getStatusCode().value();
            result = status == ACCEPTED ? new HandoverResult.HandedOver() : new HandoverResult.Failed("HTTP " + status);
        } catch (RestClientResponseException e) {
            result = new HandoverResult.Failed("HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) { // no response (refused, timeout) or anything unexpected
            result = new HandoverResult.Failed(e.getClass().getSimpleName());
        }
        return result;
    }
}
