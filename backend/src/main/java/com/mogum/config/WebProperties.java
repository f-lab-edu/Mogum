package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

@ConfigurationProperties("app.web")
public record WebProperties(@DefaultValue("http://localhost:5173") String origin,
                            @DefaultValue("") String cookieDomain) {
    public WebProperties {
        var uri = URI.create(origin);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("app.web.origin must be one HTTP origin without a path");
        }
        cookieDomain = cookieDomain == null || cookieDomain.isBlank() ? "" : cookieDomain;
    }
}
