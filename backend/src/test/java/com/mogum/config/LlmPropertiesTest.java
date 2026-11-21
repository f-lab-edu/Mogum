package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LlmPropertiesTest {
	private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	@DisplayName("계획 채팅과 단가 및 DB 여유는 실제 설정에서 읽는다")
	void bindsPlanningSettings() {
		// when
		applicationContextRunner.run(context -> {
			// then
			assertThat(context).hasNotFailed();
			var properties = context.getBean(LlmProperties.class);
			var planning = properties.planning();
			assertThat(planning).isSameAs(properties.chats().get("planning"));
			assertThat(planning.model()).isEqualTo("gpt-5.6-terra");
			assertThat(planning.maxCompletionTokens()).isEqualTo(4000);
			assertThat(planning.timeout()).isEqualTo(Duration.ofSeconds(50));
			assertThat(planning.maxContinuations()).isEqualTo(1);
			assertThat(properties.databaseAllowance()).isEqualTo(Duration.ofSeconds(10));
			assertThat(properties.prices().get(planning.model()).input()).isEqualByComparingTo("2.00");
			assertThat(properties.prices().get(planning.model()).cachedInput()).isEqualByComparingTo("0.20");
			assertThat(properties.prices().get(planning.model()).output()).isEqualByComparingTo("12.00");
		});
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"app.llm.openai.api-key=", "app.llm.openai.api-key=   ",
		"app.llm.chats.planning.model=unpriced", "app.llm.chats.planning.max-completion-tokens=0",
		"app.llm.chats.planning.timeout=0s", "app.llm.chats.planning.max-continuations=-1",
		"app.llm.database-allowance=0s", "app.llm.prices[gpt-5.6-terra].input=-1"})
	@DisplayName("빈 키나 단가 없는 모델 및 잘못된 채팅 설정은 기동을 거부한다")
	void rejectsInvalidSettings(String property) {
		// given
		var invalid = applicationContextRunner.withPropertyValues(property);

		// when
		invalid.run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
		});
	}

	@Test
	@DisplayName("다른 채팅 묶음만 있고 planning이 없으면 설정 바인딩으로 기동을 거부한다")
	void rejectsMissingPlanningSettings() {
		// given
		var runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("app.llm.openai.api-key=fake-key", "app.llm.database-allowance=10s",
				"app.llm.chats.other.model=test-model", "app.llm.chats.other.max-completion-tokens=4000",
				"app.llm.chats.other.timeout=50s", "app.llm.chats.other.max-continuations=1",
				"app.llm.prices[test-model].input=1", "app.llm.prices[test-model].cached-input=1",
				"app.llm.prices[test-model].output=1");

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("Planning chat settings must be set");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LlmProperties.class)
	static class PropertiesConfiguration {}
}
