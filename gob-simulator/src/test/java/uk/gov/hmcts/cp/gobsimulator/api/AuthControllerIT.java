package uk.gov.hmcts.cp.gobsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class AuthControllerIT {

    @Resource
    private MockMvc mockMvc;

    @Test
    void issues_a_bearer_token_for_client_credentials() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .formField("grant_type", "client_credentials")
                        .formField("client_id", "cp-test-client")
                        .formField("client_secret", "not-a-real-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(conformsToSpec());
    }

    @Test
    void issues_a_token_without_checking_the_credentials() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .formField("grant_type", "client_credentials")
                        .formField("client_id", "anything")
                        .formField("client_secret", "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }
}
