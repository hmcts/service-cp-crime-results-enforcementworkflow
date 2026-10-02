package uk.gov.hmcts.cp.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class ActuatorIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;


    @Test
    void actuator_info_should_have_build_fields() throws Exception {
        final String name = "service-cp-crime-results-enforcementworkflow";
        mockMvc.perform(get("/actuator/info"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value(name))
                .andExpect(jsonPath("$.build.name").value(name))
                .andExpect(jsonPath("$.build.time").exists())
                .andExpect(jsonPath("$.build.version").exists());
    }

    @Test
    void actuator_info_should_have_gorylenko_git_fields() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.branch").exists())
                .andExpect(jsonPath("$.git.commit.id").exists())
                .andExpect(jsonPath("$.git.commit.time").exists());
    }

    @Test
    void actuator_health_should_have_correct_fields() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.groups[0]").value("liveness"))
                .andExpect(jsonPath("$.groups[1]").value("readiness"));
    }

    // No OTLP collector runs in our environments: pushing every minute only logged "Failed to publish metrics to
    // OTLP receiver". Metrics are still scraped from /actuator/prometheus. OTLP_METRICS_EXPORT_ENABLED turns it on.
    @Test
    void otlp_metrics_push_should_be_off_by_default() {
        assertThat(applicationContext.getBeansOfType(MeterRegistry.class).values())
                .extracting(registry -> registry.getClass().getSimpleName())
                .noneMatch(name -> name.startsWith("Otlp"));
    }
}
