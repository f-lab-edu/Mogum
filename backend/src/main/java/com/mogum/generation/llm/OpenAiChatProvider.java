package com.mogum.generation.llm;

import java.time.Duration;

import com.mogum.generation.ChatProvider;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/** SDK 예외와 프로바이더 응답은 이 경계 밖으로 내보내지 않는다. 자동 재시도는 없다. */
public final class OpenAiChatProvider implements ChatProvider, AutoCloseable {
	private final OpenAIClient openAiClient;

	public OpenAiChatProvider(String apiKey, String baseUrl, Duration timeout) {
		var builder = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0).timeout(timeout);
		if (baseUrl != null && !baseUrl.isBlank()) {
			builder.baseUrl(baseUrl);
		}
		openAiClient = builder.build();
	}

	@Override
	public Reply send(Request request) {
		Usage usage = null;
		try {
			var params = ChatCompletionCreateParams.builder()
				.model(request.settings().model())
				.maxCompletionTokens(request.settings().maxCompletionTokens())
				.reasoningEffort(ReasoningEffort.NONE);
			for (var message : request.messages()) {
				switch (message.role()) {
					case SYSTEM -> params.addSystemMessage(message.text());
					case USER -> params.addUserMessage(message.text());
					case ASSISTANT -> params.addAssistantMessage(message.text());
				}
			}

			var response = openAiClient.chat().completions().create(params.build(),
				RequestOptions.builder().timeout(request.settings().timeout()).build());
			if (response.usage().isPresent()) {
				var tokens = response.usage().get();
				usage = new Usage(Math.toIntExact(tokens.promptTokens()),
					tokens.promptTokensDetails().flatMap(details -> details.cachedTokens())
						.map(Math::toIntExact).orElse(null),
					Math.toIntExact(tokens.completionTokens()));
			}
			if (response.choices().isEmpty()) {
				return invalid(usage);
			}

			var choice = response.choices().getFirst();
			String text = choice.message().content().orElse("");
			if (text.isBlank()) {
				return invalid(usage);
			}
			if (choice.finishReason().asString().equals("stop")) {
				return new Reply(text, LlmCallOutcome.SUCCEEDED, null, usage);
			}
			if (choice.finishReason().asString().equals("length")) {
				return new Reply(text, LlmCallOutcome.TRUNCATED, null, usage);
			}
			return invalid(usage);
		} catch (OpenAIServiceException exception) {
			return rejected(exception);
		} catch (OpenAIIoException exception) {
			return new Reply(null,
				OpenAiFailures.timedOut(exception) ? LlmCallOutcome.TIMEOUT : LlmCallOutcome.PROVIDER_ERROR,
				GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, usage);
		} catch (RuntimeException exception) {
			// SDK JsonHandler는 본문 읽기 중의 시간 초과도 데이터 예외로 감싼다.
			if (OpenAiFailures.timedOut(exception)) {
				return new Reply(null, LlmCallOutcome.TIMEOUT, GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, usage);
			}
			// JSON 해석·스키마 오류에는 원문이 들어 있을 수 있다. 메시지는 사용하지 않는다.
			return invalid(usage);
		}
	}

	/** 임베딩 어댑터와 같은 키 · 기준 주소 · HTTP 풀을 쓴다. 소유자는 이 어댑터다. */
	OpenAIClient client() {
		return openAiClient;
	}

	private static Reply rejected(OpenAIServiceException exception) {
		var failure = OpenAiFailures.rejected(exception);
		return new Reply(null, failure.outcome(), failure.failure(), null);
	}

	private static Reply invalid(Usage usage) {
		return new Reply(null, LlmCallOutcome.INVALID_OUTPUT, GenerationOutcome.Failure.INVALID_OUTPUT, usage);
	}

	@Override
	public void close() {
		openAiClient.close();
	}
}
