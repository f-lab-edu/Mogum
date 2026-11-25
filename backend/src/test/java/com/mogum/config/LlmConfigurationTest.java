package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.mogum.generation.ChatCall;
import com.mogum.generation.ChatProvider;
import com.mogum.generation.EmbeddingCall;
import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.call.LlmCallWriter;

class LlmConfigurationTest {
	private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(LlmConfiguration.class, LlmPropertiesTest.PropertiesConfiguration.class,
			WorkerPropertiesTest.PropertiesConfiguration.class)
		.withBean(LlmCallWriter.class, () -> mock(LlmCallWriter.class));

	@Test
	@DisplayName("실제 설정은 시한 식을 만족하며 어댑터와 채팅 호출을 조립한다")
	void wiresClientWithSafeLease() {
		// when
		applicationContextRunner.run(context -> {
			// then
			assertThat(context).hasNotFailed().hasSingleBean(ChatProvider.class).hasSingleBean(ChatCall.class)
				.hasSingleBean(EmbeddingProvider.class).hasSingleBean(EmbeddingCall.class);
			assertThat(context.getBean(WorkerProperties.class).leaseDuration())
				.isEqualTo(Duration.ofSeconds(1020)).isGreaterThan(Duration.ofSeconds(972));
			var llm = context.getBean(LlmProperties.class);
			for (var settings : llm.chats().values()) {
				assertThat(context.getBean(WorkerProperties.class).shutdownWait()).isGreaterThan(settings.timeout());
			}
		});
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"app.worker.lease-duration=15m", "app.worker.lease-duration=972s",
		"app.worker.max-retries=3", "app.worker.retry-delay=30s",
		"app.llm.chats.planning.timeout=149s", "app.llm.chats.planning.max-continuations=5",
		"app.llm.chats.material.timeout=149s", "app.llm.chats.material.max-continuations=2",
		"app.llm.embedding.timeout=36s", "app.llm.database-allowance=58s",
		"app.worker.shutdown-wait=140s", "app.worker.shutdown-wait=50s"})
	@DisplayName("시한 식의 엄격한 부등식이나 호출 종료 여유를 깨면 기동을 거부한다")
	void rejectsUnsafeTiming(String property) {
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
	@DisplayName("임베딩을 포함한 시한 하한보다 1초 길면 기동한다")
	void acceptsLeaseJustAboveRequiredDuration() {
		// given
		var runner = applicationContextRunner.withPropertyValues("app.worker.lease-duration=973s");

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasNotFailed();
		});
	}

	@ParameterizedTest(name = "{0}초")
	@ValueSource(ints = {149, 150})
	@DisplayName("추가된 모든 채팅 묶음 중 가장 긴 시간을 검사한다")
	void validatesEveryChatGroup(int timeout) {
		// given
		var invalid = applicationContextRunner.withPropertyValues(
			"app.llm.chats.other.model=gpt-5.6-terra",
			"app.llm.chats.other.max-completion-tokens=4000", "app.llm.chats.other.timeout=" + timeout + "s",
			"app.llm.chats.other.max-continuations=1");

		// when
		invalid.run(context -> {
			// then
			assertThat(context).hasFailed();
		});
	}
}
