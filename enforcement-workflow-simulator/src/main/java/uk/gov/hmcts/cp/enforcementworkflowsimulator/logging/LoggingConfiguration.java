package uk.gov.hmcts.cp.enforcementworkflowsimulator.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Scopes {@link HearingTrafficLoggingFilter} to the {@code HearingController} endpoints only. */
@Configuration
public class LoggingConfiguration {

    @Bean
    public FilterRegistrationBean<HearingTrafficLoggingFilter> hearingTrafficLoggingFilter() {
        final FilterRegistrationBean<HearingTrafficLoggingFilter> registration =
                new FilterRegistrationBean<>(new HearingTrafficLoggingFilter());
        registration.addUrlPatterns("/hearing", "/hearing/result");
        return registration;
    }
}
