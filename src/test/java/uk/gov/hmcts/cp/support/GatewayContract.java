package uk.gov.hmcts.cp.support;

import com.networknt.schema.JsonSchema;

import java.util.Set;

/**
 * Validates payloads against the enforcement gateway contract: {@value #CONTRACT} inside the
 * {@code api-cp-crime-results-enforcementgateway} jar this service is built against, so the check
 * follows the contract version in build.gradle (research.md R25).
 */
public final class GatewayContract {

    private static final String CONTRACT = "openapi/openapi-spec.yml";
    private static final JsonSchema HEARING_RESULTED_REQUEST = OpenApiSchemas.load(CONTRACT, "HearingResultedRequest");
    private static final JsonSchema HEARING_RESULTED_RESPONSE = OpenApiSchemas.load(CONTRACT, "HearingResultedResponse");

    private GatewayContract() {
    }

    /** The schema violations for a HearingResultedRequest JSON document (empty = valid). */
    public static Set<String> hearingResultedRequestViolations(final String json) {
        return OpenApiSchemas.violations(HEARING_RESULTED_REQUEST, json);
    }

    /** The schema violations for a HearingResultedResponse JSON document (empty = valid). */
    public static Set<String> hearingResultedResponseViolations(final String json) {
        return OpenApiSchemas.violations(HEARING_RESULTED_RESPONSE, json);
    }
}
