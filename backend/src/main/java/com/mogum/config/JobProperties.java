package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.job")
public record JobProperties(int maxInProgressPerUser) {
}
