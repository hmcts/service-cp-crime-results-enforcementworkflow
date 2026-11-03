package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup refuses missing or placeholder system user ids, and states where the pod is pointed (hosts only). */
@ExtendWith(OutputCaptureExtension.class)
class StartupPropertiesValidatorTest {

    private static final String RESULTS_USER = "5f1a6c2e-0000-4000-8000-00000000aa01";
    private static final String REFDATA_USER = "5f1a6c2e-0000-4000-8000-00000000aa02";
    private static final String EVENT_USER = "5f1a6c2e-0000-4000-8000-00000000aa03";

    private final ResultsProperties results = new ResultsProperties();
    private final ClientProperties clients = new ClientProperties();

    private StartupPropertiesValidator validator(final String resultsUser, final String refdataUser, final String eventUser) {
        results.getResults().setCjscppuid(resultsUser);
        results.getResults().setBaseUrl("http://ste01-internal.ingress01.ste.nl.example/path?token=secret");
        clients.getReferenceData().setCjscppuid(refdataUser);
        clients.getEnforcementGateway().setBaseUrl("http://resultsenforcementgateway-service:8082");
        return new StartupPropertiesValidator(results, clients, eventUser, "public.event", "tcp://artemis-artemis-activemq:61616",
                "jdbc:postgresql://psf-ste-ccm01-results.postgres.example:5432/enforcementworkflowdb");
    }

    @Test
    void real_system_user_ids_should_start() {
        assertThatCode(() -> validator(RESULTS_USER, REFDATA_USER, EVENT_USER).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void missing_results_id_should_not_start() {
        assertThatThrownBy(() -> validator("", REFDATA_USER, EVENT_USER).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESULTS_CJSCPPUID");
    }

    @Test
    void placeholder_reference_data_id_should_not_start() {
        assertThatThrownBy(() -> validator(RESULTS_USER, "00000000-0000-0000-0000-000000000000", EVENT_USER).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REFERENCEDATA_CJSCPPUID")
                .hasMessageContaining("placeholder");
    }

    @Test
    void malformed_event_user_should_not_start_and_should_not_be_echoed() {
        assertThatThrownBy(() -> validator(RESULTS_USER, REFDATA_USER, "not-a-uuid").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CP_MESSAGING_SYSTEM_USER")
                .hasMessageNotContaining("not-a-uuid");
    }

    @Test
    void startup_line_should_carry_hosts_only(final CapturedOutput output) {
        validator(RESULTS_USER, REFDATA_USER, EVENT_USER).afterPropertiesSet();

        assertThat(output).contains("Effective configuration.")
                .contains("resultsHost=ste01-internal.ingress01.ste.nl.example")
                .contains("enforcementGatewayHost=resultsenforcementgateway-service")
                .contains("brokerHost=artemis-artemis-activemq")
                .contains("databaseHost=psf-ste-ccm01-results.postgres.example")
                .contains("resultsCacheEnabled=false")
                .doesNotContain("token=secret")
                .doesNotContain(RESULTS_USER).doesNotContain(REFDATA_USER).doesNotContain(EVENT_USER);
    }

    @Test
    void unreadable_url_should_show_as_unknown_host() {
        assertThat(StartupPropertiesValidator.hostOf("")).isEqualTo("?");
        assertThat(StartupPropertiesValidator.hostOf("not a url")).isEqualTo("?");
    }
}
