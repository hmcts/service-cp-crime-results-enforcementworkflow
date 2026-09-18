package uk.gov.hmcts.cp.gobsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingControllerIT {

    @Resource
    private MockMvc mockMvc;

    @Test
    void accepts_a_hearing_confirmation() throws Exception {
        mockMvc.perform(post("/hearing")
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
    void rejects_a_confirmation_missing_the_mandatory_court_location() throws Exception {
        mockMvc.perform(post("/hearing")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "caseUrn": "E011122334" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(conformsToSpec());
    }
}
