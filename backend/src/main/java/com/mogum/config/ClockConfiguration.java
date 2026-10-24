package com.mogum.config;

import com.mogum.common.SeoulClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SeoulClock seoulClock(Clock clock) {
        return new SeoulClock(clock);
    }
}
