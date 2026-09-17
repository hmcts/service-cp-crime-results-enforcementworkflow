package uk.gov.hmcts.cp.gobsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import uk.gov.hmcts.cp.gobsimulator.guard.LiveEnvironmentGuard;

@SpringBootApplication
public class GobSimulatorApplication {

    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(GobSimulatorApplication.class);
        application.addListeners(new LiveEnvironmentGuard());
        application.run(args);
    }
}
