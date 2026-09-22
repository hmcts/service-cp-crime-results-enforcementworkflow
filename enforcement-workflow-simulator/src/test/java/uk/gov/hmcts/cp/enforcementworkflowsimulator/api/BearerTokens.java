package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completes the client-credentials exchange the way Common Platform has to, and hands back the
 * header value.
 *
 * <p>Tests fetch a real token rather than hard-coding a plausible-looking string on purpose: a
 * hard-coded token would pass a presence-only check and keep passing if {@link
 * uk.gov.hmcts.cp.enforcementworkflowsimulator.security.TokenStore} stopped recording issued tokens altogether,
 * which is the regression these tests are meant to catch.
 */
final class BearerTokens {

    private BearerTokens() {
    }

    /** Returns a ready-to-send {@code Authorization} value — {@code "Bearer <token>"}. */
    static String authorizationHeader(final MockMvc mockMvc) throws Exception {
        final String body = mockMvc.perform(post("/auth/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .formField("grant_type", "client_credentials")
                        .formField("client_id", "cp-test-client")
                        .formField("client_secret", "not-a-real-secret"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return "Bearer " + JsonPath.<String>read(body, "$.access_token");
    }
}
