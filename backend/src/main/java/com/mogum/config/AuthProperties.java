package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app.auth")
public record AuthProperties(List<String> adminEmails) {
    public AuthProperties {
        adminEmails = adminEmails == null ? List.of() : List.copyOf(adminEmails);
    }
}
