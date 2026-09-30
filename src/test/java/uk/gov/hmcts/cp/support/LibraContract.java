package uk.gov.hmcts.cp.support;

import com.networknt.schema.JsonSchema;

import java.util.Set;

/**
 * Validates outbound payloads against the Libra Gateway contract, a test-resource copy at {@value #CONTRACT} (constitution
 * Principle VII: payloads built for external contracts are validated against the contract schema).
 */
public final class LibraContract {

    /** Test resource copy of the Libra contract (with the documented local amendment: nowsDataRequest optional). */
    private static final String CONTRACT = "contracts/libra-gateway-hearing-events-openapi-v0.4.0.yml";
    private static final JsonSchema HEARING_RESULTED_REQUEST = OpenApiSchemas.load(CONTRACT, "HearingResultedRequest");

    private LibraContract() {
    }

    /** Returns the schema violations for a HearingResultedRequest JSON document (empty = valid). */
    public static Set<String> hearingResultedRequestViolations(final String json) {
        return OpenApiSchemas.violations(HEARING_RESULTED_REQUEST, json);
    }
}
