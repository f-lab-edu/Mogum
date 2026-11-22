package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

	@Test
	@DisplayName("강의 자료 채팅과 입력 전용 임베딩 단가를 실제 설정에서 읽는다")
	void bindsMaterialAndEmbeddingSettings() {
		// when
		applicationContextRunner.run(context -> {
			// then
			assertThat(context).hasNotFailed();
			var properties = context.getBean(LlmProperties.class);
			var material = properties.material();
			assertThat(material).isSameAs(properties.chats().get("material"));
			assertThat(material.model()).isEqualTo("gpt-5.6-terra");
			assertThat(material.maxCompletionTokens()).isEqualTo(10000);
			assertThat(material.timeout()).isEqualTo(Duration.ofSeconds(140));
			assertThat(material.maxContinuations()).isEqualTo(1);
			assertThat(properties.embedding().model()).isEqualTo("text-embedding-3-large");
			assertThat(properties.embedding().timeout()).isEqualTo(Duration.ofSeconds(20));
			var prices = properties.prices().get(properties.embedding().model());
			assertThat(prices.input()).isEqualByComparingTo("0.13");
			assertThat(prices.cachedInput()).isZero();
			assertThat(prices.output()).isZero();
		});
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"app.llm.openai.api-key=", "app.llm.openai.api-key=   ",
		"app.llm.chats.planning.model=unpriced", "app.llm.chats.planning.max-completion-tokens=0",
		"app.llm.chats.planning.timeout=0s", "app.llm.chats.planning.max-continuations=-1",
		"app.llm.database-allowance=0s", "app.llm.prices[gpt-5.6-terra].input=-1",
		"app.llm.chats.material.model=unpriced", "app.llm.chats.material.model=   ",
		"app.llm.chats.material.max-completion-tokens=0", "app.llm.chats.material.timeout=-1s",
		"app.llm.chats.material.max-continuations=-1", "app.llm.embedding.model=unpriced",
		"app.llm.embedding.model=", "app.llm.embedding.model=   ",
		"app.llm.embedding.timeout=0s", "app.llm.embedding.timeout=-1s",
		"app.llm.prices[text-embedding-3-large].input=-1"})
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

	@ParameterizedTest(name = "{0}")
	@CsvSource({"app.llm.chats.planning., Planning chat settings must be set",
		"app.llm.chats.material., Material chat settings must be set",
		"app.llm.embedding., Embedding settings must be set",
		"app.llm.embedding.model=, Embedding model must be set",
		"app.llm.embedding.timeout=, Embedding timeout must be positive",
		"app.llm.prices[test-model]., Every chat model must have token prices",
		"app.llm.prices[embedding-model]., Embedding model must have token prices"})
	@DisplayName("필수 채팅과 임베딩 설정 및 모델 단가가 빠지면 기동을 거부한다")
	void rejectsMissingSettings(String missingPrefix, String message) {
		// given
		var settings = List.of("app.llm.openai.api-key=fake-key", "app.llm.database-allowance=10s",
			"app.llm.chats.planning.model=test-model", "app.llm.chats.planning.max-completion-tokens=4000",
			"app.llm.chats.planning.timeout=50s", "app.llm.chats.planning.max-continuations=1",
			"app.llm.chats.material.model=test-model", "app.llm.chats.material.max-completion-tokens=10000",
			"app.llm.chats.material.timeout=140s", "app.llm.chats.material.max-continuations=1",
			"app.llm.embedding.model=embedding-model", "app.llm.embedding.timeout=20s",
			"app.llm.prices[test-model].input=1", "app.llm.prices[test-model].cached-input=1",
			"app.llm.prices[test-model].output=1", "app.llm.prices[embedding-model].input=0.13",
			"app.llm.prices[embedding-model].cached-input=0", "app.llm.prices[embedding-model].output=0");
		var runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues(settings.stream().filter(value -> !value.startsWith(missingPrefix))
				.toArray(String[]::new));

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining(message);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LlmProperties.class)
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	static class PropertiesConfiguration {}
	// @checkstyle:on
}
