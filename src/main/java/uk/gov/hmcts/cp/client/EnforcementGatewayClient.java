package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.openapi.api.EnforcementHearingApi;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

/**
 * Calls the enforcement gateway's {@code POST /hearingResulted} (contracts/gateway-post-hearing-resulted.md).
 * The gateway forwards to APIM/Libra and returns Libra's body unchanged. This client never throws: every
 * outcome is a {@link GatewayResult}. It uses the gateway builder (connect 5s / read 50s), which gives up
 * after the gateway's own 45s budget (research.md R20).
 * <ul>
 *   <li>Connection not established (refused, connect timeout, unknown host) means nothing was sent:
 *       {@value #NOT_SENT_UNREACHABLE}.</li>
 *   <li>A failure after the request may have left means the outcome at GOB is unknown:
 *       {@value #OUTCOME_UNKNOWN_TIMEOUT}.</li>
 *   <li>A 2xx whose body can't be parsed is still a success. GOB accepted it, and the raw body is kept.</li>
 *   <li>A 3xx is not followed, and is a failure with an unknown outcome: {@value #OUTCOME_UNKNOWN_ERROR}.</li>
 *   <li>Error bodies are reduced to the gateway/Libra codes. Libra's free-text {@code errorDescription} is
 *       not kept, because it may echo payload values (PII).</li>
 * </ul>
 * Only the outcome is logged. TEMP DIAGNOSTICS (test environments only, to be removed before release to higher
 * environments): failures also log the url, the exception message, root cause and stack trace, and for an error
 * response the gateway's message and Libra's errorDescription (log only, never stored); see {@link TempDiagnostics}.
 */
@Slf4j
@Component
public class EnforcementGatewayClient {

    public static final String OUTCOME_UNKNOWN_TIMEOUT = "TIMEOUT – outcome at GOB unknown";
    public static final String NOT_SENT_UNREACHABLE = "NOT_SENT – enforcement gateway unreachable";
    public static final String OUTCOME_UNKNOWN_ERROR = "ERROR – outcome at GOB unknown";

    private final RestClient restClient;
    // TEMP DIAGNOSTICS: kept only to log the url on failures
    private final String url;

    public EnforcementGatewayClient(@Qualifier("enforcementGatewayRestClientBuilder") final RestClient.Builder restClientBuilder,
                                    @Value("${cp.enforcement-gateway.base-url}") final String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.url = baseUrl + EnforcementHearingApi.PATH_POST_HEARING_RESULTED;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the contract of this client is "never throws"
    public GatewayResult submit(final HearingResultedRequest request) {
        GatewayResult result;
        try {
            final ResponseEntity<String> response = restClient.post()
                    .uri(EnforcementHearingApi.PATH_POST_HEARING_RESULTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(PayloadJson.MAPPER.writeValueAsString(request))
                    .retrieve()
                    .toEntity(String.class);
            final int status = response.getStatusCode().value();
            // only a 2xx is a success; a 3xx (not followed) means the gateway may never have seen it
            if (response.getStatusCode().is2xxSuccessful()) {
                result = new GatewayResult.Success(parse(response.getBody()), response.getBody(), status);
            } else {
                // TEMP DIAGNOSTICS: a 3xx was not logged at all - log the status and where it pointed
                log.warn("Enforcement gateway answered caseUrn {} with HTTP {} (not followed; url={}, location={})",
                        request.getCaseUrn(), status, url, response.getHeaders().getLocation());
                result = new GatewayResult.Failure(status, OUTCOME_UNKNOWN_ERROR + ": HTTP " + status);
            }
        } catch (RestClientResponseException e) {
            final String summary = summarise(e.getResponseBodyAsString());
            result = new GatewayResult.Failure(e.getStatusCode().value(), summary);
            // TEMP DIAGNOSTICS: the gateway's error was not logged here (only recorded as codes) - log the status, url,
            // the gateway's message and Libra's errorDescription, plus a stack trace without the response body
            log.warn("Enforcement gateway rejected caseUrn {}: HTTP {} {} (url={}, contentType={}, {}; {})",
                    request.getCaseUrn(), e.getStatusCode().value(), e.getStatusText(), url, TempDiagnostics.contentType(e),
                    summary, errorDescriptions(e.getResponseBodyAsString()), TempDiagnostics.withoutResponseBody(e));
        } catch (ResourceAccessException e) {
            final boolean notSent = hasCause(e, ConnectException.class) || hasCause(e, HttpConnectTimeoutException.class)
                    || hasCause(e, UnknownHostException.class);
            // TEMP DIAGNOSTICS: url, message, root cause and stack trace added
            final Throwable rootCause = TempDiagnostics.rootCause(e);
            log.warn("Enforcement gateway gave no response for caseUrn {}: {} ({}) - {} (url={}, rootCause={}: {})",
                    request.getCaseUrn(), e.getClass().getSimpleName(), notSent ? "not sent" : "outcome unknown",
                    e.getMessage(), url, rootCause.getClass().getName(), rootCause.getMessage(), e);
            result = new GatewayResult.Failure(null, notSent ? NOT_SENT_UNREACHABLE : OUTCOME_UNKNOWN_TIMEOUT);
        } catch (RuntimeException e) { // other RestClientException subtypes, JSON writing, anything unexpected
            // TEMP DIAGNOSTICS: url, message, root cause and stack trace added
            final Throwable rootCause = TempDiagnostics.rootCause(e);
            log.warn("Enforcement gateway call failed for caseUrn {}: {} - {} (url={}, rootCause={}: {})",
                    request.getCaseUrn(), e.getClass().getSimpleName(), e.getMessage(), url,
                    rootCause.getClass().getName(), rootCause.getMessage(), e);
            result = new GatewayResult.Failure(null, OUTCOME_UNKNOWN_ERROR + ": " + e.getClass().getSimpleName());
        }
        return result;
    }

    /** Null when the 2xx body is empty or not a HearingResultedResponse; the raw body is still stored. */
    private static HearingResultedResponse parse(final String body) {
        HearingResultedResponse response = null;
        if (body != null && !body.isBlank()) {
            try {
                response = PayloadJson.MAPPER.readValue(body, HearingResultedResponse.class);
            } catch (JacksonException e) {
                // TEMP DIAGNOSTICS: the parser's own message added (getOriginalMessage: without the body excerpt)
                log.warn("Enforcement gateway 2xx body could not be parsed as HearingResultedResponse: {} - {}",
                        e.getClass().getSimpleName(), e.getOriginalMessage());
            }
        }
        return response;
    }

    /** Gateway ErrorResponse reduced to codes: {@code error}, {@code details.libraStatus}, {@code details.errorCode}. */
    private static String summarise(final String body) {
        String summary;
        try {
            final JsonNode json = PayloadJson.MAPPER.readTree(body);
            final JsonNode details = json.path("details");
            summary = "error=" + json.path("error").asString("") + "; libraStatus=" + details.path("libraStatus").asString("")
                    + "; errorCode=" + details.path("errorCode").asString("");
        } catch (JacksonException | IllegalArgumentException e) {
            summary = "non-JSON error body omitted";
        }
        return summary;
    }

    /**
     * TEMP DIAGNOSTICS: the gateway's {@code message} and Libra's {@code details.errorDescription}, for the log only.
     * They are never stored ({@link #summarise} keeps codes only), because Libra's free text may echo payload values.
     */
    private static String errorDescriptions(final String body) {
        String text;
        try {
            final JsonNode json = PayloadJson.MAPPER.readTree(body);
            text = "message=" + json.path("message").asString("") + "; errorDescription="
                    + json.path("details").path("errorDescription").asString("");
        } catch (JacksonException | IllegalArgumentException e) {
            text = "non-JSON error body";
        }
        return text;
    }

    private static boolean hasCause(final Throwable error, final Class<? extends Throwable> type) {
        Throwable current = error;
        boolean found = false;
        while (current != null && !found) {
            found = type.isInstance(current);
            current = current.getCause();
        }
        return found;
    }
}
