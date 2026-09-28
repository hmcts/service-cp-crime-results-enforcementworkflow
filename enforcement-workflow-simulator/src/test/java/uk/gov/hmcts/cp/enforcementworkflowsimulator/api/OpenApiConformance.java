package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.springframework.test.web.servlet.ResultMatcher;

/** Shared access to the bundled Libra Gateway contract for conformance assertions. */
public final class OpenApiConformance {

    public static final String SPEC_PATH = "openapi/libra-gateway-hearing-events-v0.4.0.yml";

    private OpenApiConformance() {
    }

    /** Asserts the response conforms to the spec's definition of the given operation. */
    public static ResultMatcher conformsToSpec() {
        return OpenApiValidationMatchers.openApi().isValid(SPEC_PATH);
    }
}
