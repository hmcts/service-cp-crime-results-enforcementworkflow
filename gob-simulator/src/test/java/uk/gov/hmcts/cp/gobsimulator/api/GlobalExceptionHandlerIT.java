package uk.gov.hmcts.cp.gobsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class GlobalExceptionHandlerIT {

    @Resource
    private MockMvc mockMvc;

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
        // /hearing consumes only application/json.
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
