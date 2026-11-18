package uk.gov.hmcts.cp.support;

import com.networknt.schema.JsonSchema;

import java.util.Set;

/**
 * Validates outbound payloads against the Libra Gateway contract, a test-resource copy at {@value #CONTRACT} (constitution
 * Principle VII: payloads built for external contracts are validated against the contract schema).
 */
public final class LibraContract {

    /**
     * Libra's published contract v0.8.0, unchanged (copied 2026-11-18 from specs/docs, CIMD-4251). It replaces the amended
     * v0.4.0 copy: v0.8.0 already has paymentTerms and nowsDataRequest optional and resultCode without an enum.
     */
    private static final String CONTRACT = "contracts/libra-gateway-hearing-events-openapi-v0.8.0.yml";
    private static final JsonSchema HEARING_RESULTED_REQUEST = OpenApiSchemas.load(CONTRACT, "HearingResultedRequest");

    private LibraContract() {
    }

    /** Returns the schema violations for a HearingResultedRequest JSON document (empty = valid). */
    public static Set<String> hearingResultedRequestViolations(final String json) {
        return OpenApiSchemas.violations(HEARING_RESULTED_REQUEST, json);
    }
}
