package com.mogum.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import com.mogum.generation.ChatCall;
import com.mogum.generation.ChatProvider;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.llm.OpenAiChatProvider;

@Configuration(proxyBeanMethods = false)
public class LlmConfiguration {
	public LlmConfiguration(LlmProperties llmProperties, WorkerProperties workerProperties) {
		var longest = Duration.ZERO;
		for (var settings : llmProperties.chats().values()) {
			var attempt = settings.timeout().multipliedBy((long)settings.maxContinuations() + 1);
			if (attempt.compareTo(longest) > 0)
				longest = attempt;

			Assert.isTrue(workerProperties.shutdownWait().compareTo(settings.timeout()) > 0,
				"Worker shutdown wait must exceed every chat timeout");
		}

		// 이어 쓰기 간격은 0이다. R은 첫 시도 뒤 추가 다시 시도 횟수다.
		var required = longest.multipliedBy((long)workerProperties.maxRetries() + 1)
			.plus(workerProperties.retryDelay().multipliedBy(workerProperties.maxRetries()))
			.plus(llmProperties.databaseAllowance());
		Assert.isTrue(workerProperties.leaseDuration().compareTo(required) > 0,
			"Worker lease must exceed retries, chat calls and database allowance");
	}

	@Bean(destroyMethod = "close")
	public OpenAiChatProvider chatProvider(LlmProperties llmProperties) {
		var timeout = llmProperties.chats().values().stream()
			.map(settings -> settings.timeout())
			.max(Duration::compareTo)
			.orElseThrow();
		return new OpenAiChatProvider(llmProperties.openai().apiKey(), llmProperties.openai().baseUrl(), timeout);
	}

	@Bean
	public ChatCall chatCall(ChatProvider chatProvider, LlmCallWriter llmCallWriter, LlmProperties llmProperties) {
		return new ChatCall(chatProvider, llmCallWriter, llmProperties.prices());
	}
}
