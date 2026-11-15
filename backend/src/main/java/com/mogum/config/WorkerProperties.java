package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.worker")
public record WorkerProperties(Duration leaseDuration, Duration renewalInterval, int maxRetries, Duration retryDelay) {
    public WorkerProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || renewalInterval == null || renewalInterval.isNegative() || renewalInterval.isZero()
                || maxRetries < 0 || retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Invalid worker timing settings");
        }
    }
}
