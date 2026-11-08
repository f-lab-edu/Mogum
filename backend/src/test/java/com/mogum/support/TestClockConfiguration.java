package com.mogum.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {
    @Bean
    @Primary
    public TestClock testClock() {
        return new TestClock();
    }
}
