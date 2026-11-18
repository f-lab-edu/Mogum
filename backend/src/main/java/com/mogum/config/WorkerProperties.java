package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.worker")
public record WorkerProperties(
        Duration leaseDuration, Duration renewalInterval, int maxRetries, Duration retryDelay,
        int count, Duration pollInterval, Duration shutdownWait
) {
    public WorkerProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || renewalInterval == null || renewalInterval.isNegative() || renewalInterval.isZero()
                || renewalInterval.compareTo(leaseDuration) >= 0
                || maxRetries < 0 || retryDelay == null || retryDelay.isNegative()
                || count < 0 || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || shutdownWait == null || shutdownWait.isNegative() || shutdownWait.isZero()
                // Spring 종료 단계 130초 안에 반환의 DB 처리 여유를 10초 이상 남긴다.
                || shutdownWait.compareTo(Duration.ofSeconds(120)) >= 0) {
            throw new IllegalArgumentException("Invalid worker timing settings");
        }
    }
}
