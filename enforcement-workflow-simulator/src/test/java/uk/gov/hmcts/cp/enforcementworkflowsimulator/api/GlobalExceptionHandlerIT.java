package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.NowsDataItemsAssembler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_XML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding I6: {@code GlobalExceptionHandler}'s {@code @ExceptionHandler(Exception.class)}
 * catch-all used to run ahead of Spring's own resolvers for these three routine client mistakes,
 * turning a framework 404/405/415 into a 500 with a stack trace logged at ERROR. These tests
 * pin the correct, contract-shaped status and body for each.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("enforcement-workflow-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class GlobalExceptionHandlerIT {

    @Resource
    private MockMvc mockMvc;

    /** A genuine token from /auth/token — the hearing endpoints reject anything else (ADR-004). */
    private String authorization;

    @BeforeEach
    void obtainBearerToken() throws Exception {
        authorization = BearerTokens.authorizationHeader(mockMvc);
    }

    @MockitoBean
    private NowsDataItemsAssembler assembler;

    // Finding (final wave, item 5): handleUnexpectedFailure — the @ExceptionHandler(Exception.class)
    // catch-all — was the only handler in this class with no test. A mocked assembler that throws
    // is the simplest way to reach it deterministically: it forces resultHearing() to throw
    // AFTER request validation succeeds, so this exercises the catch-all itself rather than one
    // of the specific handlers above it.
    @Test
    void returns_500_with_a_contract_shaped_body_and_no_leaked_internals_for_an_unexpected_failure()
            throws Exception {
        // Stubs the FOUR-argument overload, which is the one HearingController calls (the
        // three-argument one delegates to it). Stubbing the three-argument signature here would
        // silently never match, the mock would return null instead of throwing, and this test
        // would pass a 200 while believing it had exercised the catch-all handler.
        when(assembler.assemble(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "deliberately unexpected failure: uk.gov.hmcts.cp.enforcementworkflowsimulator.SomeInternalDetail"));

        mockMvc.perform(post("/hearing/result")
                        .header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "caseUrn": "E011122334",
                                  "dateOfHearing": "2026-05-03",
                                  "courtHearingLocation": "B01BH01",
                                  "defendantDetails": {
                                    "prosecutorDefendantId": "1234567890",
                                    "address1": "1 Example Street"
                                  },
                                  "paymentTerms": {
                                    "paymentDueDate": "2026-05-31",
                                    "paymentCardRequested": "N",
                                    "parentToPay": "N"
                                  },
                                  "enforcement": { "prisonSentenceIndicator": "N" },
                                  "results": [ { "resultCode": "SC" } ],
                                  "nowsDataRequest": {
                                    "nowsDataItems": [ { "name": "Account Balance" } ]
                                  }
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorDescription").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription")
                        .value("An unexpected error occurred while processing the request."))
                .andExpect(jsonPath("$.errorDescription", not(containsStringIgnoringCase("IllegalStateException"))))
                .andExpect(jsonPath("$.errorDescription", not(containsStringIgnoringCase("SomeInternalDetail"))))
                .andExpect(jsonPath("$.errorDescription", not(containsStringIgnoringCase("enforcementworkflowsimulator"))))
                .andExpect(jsonPath("$.errorDescription", not(containsString("\tat "))));
    }

    @Test
    void returns_404_not_500_for_an_unknown_url() throws Exception {
        mockMvc.perform(get("/this-path-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    @Test
    void returns_405_not_500_for_an_unsupported_http_method() throws Exception {
        // /hearing/result is mapped only to POST.
        mockMvc.perform(get("/hearing/result"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    @Test
    void returns_415_not_500_for_an_unsupported_content_type() throws Exception {
        // /hearing consumes only application/json. Deliberately unauthenticated: a consumes
        // mismatch is rejected during handler lookup, before any interceptor runs, so this still
        // has to be 415 rather than 401 — and that ordering is worth pinning.
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_XML)
                        .content("<hearing/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    @Test
    void still_returns_200_for_a_correctly_shaped_request() throws Exception {
        // Negative control: the new handlers must not swallow a genuinely valid request.
        mockMvc.perform(post("/hearing")
                        .header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "caseUrn": "E011122334",
                                  "courtHearingLocation": "B02BR03",
                                  "dateOfHearing": "2026-04-24",
                                  "timeOfHearing": "14:00"
                                }
                                """))
                .andExpect(status().isOk());
    }
}
