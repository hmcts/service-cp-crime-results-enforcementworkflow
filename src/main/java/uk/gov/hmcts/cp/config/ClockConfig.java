package uk.gov.hmcts.cp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** A single clock for time-based rules (the stale SENDING threshold, R19), replaceable in tests. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
