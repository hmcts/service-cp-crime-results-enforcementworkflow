package uk.gov.hmcts.cp.enforcementworkflowsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.guard.LiveEnvironmentGuard;

@SpringBootApplication
public class EnforcementWorkflowSimulatorApplication {

    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(EnforcementWorkflowSimulatorApplication.class);
        application.addListeners(new LiveEnvironmentGuard());
        application.run(args);
    }
}
