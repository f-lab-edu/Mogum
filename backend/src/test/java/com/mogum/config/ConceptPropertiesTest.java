package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConceptPropertiesTest {
	private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	@DisplayName("같은 개념 기준값은 설정에서 실측으로 정한 0.72를 읽는다")
	void readsMeasuredThreshold() {
		// when
		applicationContextRunner.run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ConceptProperties.class).sameThreshold()).isEqualTo(0.72);
		});
	}

	@ParameterizedTest(name = "유효한 기준값 {0}")
	@ValueSource(strings = {"0.0001", "0.91", "1"})
	@DisplayName("0 초과 1 이하의 기준값을 바인딩한다")
	void bindsValidThreshold(String threshold) {
		// given
		var configured = applicationContextRunner.withPropertyValues("app.concept.same-threshold=" + threshold);
		// when
		configured.run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ConceptProperties.class).sameThreshold())
				.isEqualTo(Double.parseDouble(threshold));
		});
	}

	@ParameterizedTest(name = "유효하지 않은 기준값 {0}")
	@ValueSource(strings = {"0", "-0.1", "1.0001", "NaN", "Infinity"})
	@DisplayName("기준값이 0 초과 1 이하가 아니면 기동을 거부한다")
	void rejectsInvalidThreshold(String threshold) {
		// given
		var configured = applicationContextRunner.withPropertyValues("app.concept.same-threshold=" + threshold);
		// when
		configured.run(context -> {
			// then
			assertThat(context).hasFailed();
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ConceptProperties.class)
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	static class PropertiesConfiguration {}
	// @checkstyle:on
}
