package com.mogum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.concept")
public record ConceptProperties(double sameThreshold) {
	public ConceptProperties {
		if (!(sameThreshold > 0 && sameThreshold <= 1)) {
			throw new IllegalArgumentException("concept same threshold must be greater than 0 and at most 1");
		}
	}
}
