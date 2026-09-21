package uk.gov.hmcts.cp.gobsimulator.api;

import java.util.Base64;
import java.util.UUID;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingControllerIT {

    private static final String VALID_CONFIRMATION = """
            {
              "caseUrn": "E011122334",
              "courtHearingLocation": "B02BR03",
              "dateOfHearing": "2026-04-24",
              "timeOfHearing": "14:00"
            }
            """;

    @Resource
    private MockMvc mockMvc;

    /** A genuine token from /auth/token — the hearing endpoints reject anything else (ADR-004). */
    private String authorization;

    @BeforeEach
    void obtainBearerToken() throws Exception {
        authorization = BearerTokens.authorizationHeader(mockMvc);
    }

    @Test
    void accepts_a_hearing_confirmation() throws Exception {
        mockMvc.perform(post("/hearing")
                        .header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "a1b2c3d4-1111-2222-3333-444455556666")
                        .content("""
                                {
                                  "caseUrn": "E011122334",
                                  "courtHearingLocation": "B02BR03",
                                  "dateOfHearing": "2026-04-24",
                                  "timeOfHearing": "14:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(conformsToSpec());
    }

    @Test
    void rejects_a_confirmation_with_a_blank_court_location() throws Exception {
        // courtHearingLocation is present (satisfying the schema's required-property-key check)
        // but blank, so the request itself stays contract-valid and conformsToSpec() is checking
        // only what it should here: that the 400 error BODY is contract-valid too. The blank
        // value still trips @NotBlank, which is the rejection this test exists to cover.
        mockMvc.perform(post("/hearing")
                        .header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "caseUrn": "E011122334", "courtHearingLocation": "" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(conformsToSpec());
    }

    @Test
    void rejects_a_confirmation_carrying_no_authorization_header() throws Exception {
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .content(VALID_CONFIRMATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(conformsToSpec());
    }

    @Test
    void rejects_a_confirmation_using_a_scheme_other_than_bearer() throws Exception {
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .header(AUTHORIZATION, "Basic Y3AtdGVzdC1jbGllbnQ6c2VjcmV0")
                        .content(VALID_CONFIRMATION))
                .andExpect(status().isUnauthorized())
                .andExpect(conformsToSpec());
    }

    /**
     * The token is syntactically indistinguishable from a real one — same Base64URL alphabet, same
     * length — so the only reason to reject it is that {@code AuthController} never issued it.
     * A presence-only check would pass this test, which is exactly what it exists to prevent.
     */
    @Test
    void rejects_a_confirmation_bearing_a_token_the_simulator_never_issued() throws Exception {
        final String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(UTF_8));

        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .header(AUTHORIZATION, "Bearer " + forged)
                        .content(VALID_CONFIRMATION))
                .andExpect(status().isUnauthorized())
                .andExpect(conformsToSpec());
    }

    // No conformsToSpec() here — omitting courtHearingLocation entirely also violates the
    // schema's own "required" list (a request-side violation, not just a Bean Validation one),
    // so the interaction validator would fail on the REQUEST regardless of how correct the 400
    // error body is (same reasoning as HearingResultControllerIT's two request-invalid rejection
    // tests). Asserted directly on the error body instead.
    @Test
    void rejects_a_confirmation_with_the_court_location_key_absent_entirely() throws Exception {
        mockMvc.perform(post("/hearing")
                        .header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "caseUrn": "E011122334" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }
}
