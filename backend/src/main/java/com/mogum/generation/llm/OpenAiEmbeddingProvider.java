package com.mogum.generation.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.EmbeddingCreateParams;

/** 채팅의 SDK 클라이언트를 공유한다. 자동 재시도와 이어 쓰기는 없다. */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
	private final OpenAIClient openAiClient;

	public OpenAiEmbeddingProvider(OpenAiChatProvider openAiChatProvider) {
		this.openAiClient = openAiChatProvider.client();
	}

	@Override
	public Reply send(Request request) {
		Integer inputTokens = null;
		try {
			var params = EmbeddingCreateParams.builder().model(request.settings().model())
				.inputOfArrayOfStrings(request.definitions()).build();
			var response = openAiClient.embeddings().create(params,
				RequestOptions.builder().timeout(request.settings().timeout()).build());
			inputTokens = response._usage().asKnown().map(usage -> Math.toIntExact(usage.promptTokens()))
				.orElse(null);
			List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(request.definitions().size(), null));
			for (var embedding : response.data()) {
				long index = embedding.index();
				if (index < 0 || index >= vectors.size() || vectors.get((int)index) != null) {
					return invalid(inputTokens);
				}
				embedding.embeddingValue().validate();
				final List<Float> vector;
				try {
					// SDK가 base64의 little-endian 32비트 float를 푼다. 잘못된 base64만 형식 실패다.
					vector = embedding.embedding();
				} catch (IllegalArgumentException exception) {
					return invalid(inputTokens);
				}
				vectors.set((int)index, vector);
			}
			if (vectors.contains(null)) {
				return invalid(inputTokens);
			}
			return new Reply(vectors, LlmCallOutcome.SUCCEEDED, null, inputTokens);
		} catch (OpenAIServiceException exception) {
			var failure = OpenAiFailures.rejected(exception);
			return new Reply(null, failure.outcome(), failure.failure(), inputTokens);
		} catch (OpenAIIoException exception) {
			return new Reply(null, OpenAiFailures.timedOut(exception)
				? LlmCallOutcome.TIMEOUT : LlmCallOutcome.PROVIDER_ERROR,
				GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, inputTokens);
		} catch (OpenAIInvalidDataException exception) {
			if (OpenAiFailures.timedOut(exception)) {
				return new Reply(null, LlmCallOutcome.TIMEOUT,
					GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, inputTokens);
			}
			return invalid(inputTokens);
		}
	}

	private static Reply invalid(Integer inputTokens) {
		return new Reply(null, LlmCallOutcome.INVALID_OUTPUT, GenerationOutcome.Failure.INVALID_OUTPUT, inputTokens);
	}
}
