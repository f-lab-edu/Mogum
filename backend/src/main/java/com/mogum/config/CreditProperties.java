package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.credit")
public record CreditProperties(int monthlyAmount) { }
