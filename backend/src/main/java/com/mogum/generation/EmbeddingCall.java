package com.mogum.generation;

import java.util.List;
import java.util.Map;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;

/** 한 번의 일괄 임베딩과 호출 기록. 다시 시도는 워커의 전체 시도에서만 한다. */
public final class EmbeddingCall {
	private final EmbeddingProvider embeddingProvider;
	private final LlmCallWriter llmCallWriter;
	private final Map<String, TokenPrices> prices;

	public EmbeddingCall(EmbeddingProvider embeddingProvider, LlmCallWriter llmCallWriter,
		Map<String, TokenPrices> prices) {
		this.embeddingProvider = embeddingProvider;
		this.llmCallWriter = llmCallWriter;
		this.prices = Map.copyOf(prices);
	}

	public EmbeddingResult call(JobSnapshot job, EmbeddingSettings settings, List<String> definitions) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return new EmbeddingResult.Failed(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		}
		var request = new EmbeddingProvider.Request(job.id(), job.type(), settings, definitions);
		long id = llmCallWriter.start(job.id(), job.userId(), job.type(), LlmCallType.EMBEDDING, settings.model());
		long started = System.nanoTime();
		EmbeddingProvider.Reply reply;
		try {
			reply = embeddingProvider.send(request);
		} catch (RuntimeException exception) {
			// 버그를 예상한 프로바이더 실패로 숨기지 않는다. 상위 GenerationFailures가 안전하게 기록한다.
			try {
				finish(id, LlmCallOutcome.PROVIDER_ERROR, null, settings.model(), started);
			} catch (RuntimeException recordingFailure) {
				exception.addSuppressed(recordingFailure);
			}
			throw exception;
		}
		var outcome = reply.outcome();
		if (outcome == LlmCallOutcome.SUCCEEDED && !valid(reply.vectors(), request.definitions().size())) {
			outcome = LlmCallOutcome.INVALID_OUTPUT;
		}
		finish(id, outcome, reply.inputTokens(), settings.model(), started);
		if (outcome == LlmCallOutcome.SUCCEEDED) {
			return new EmbeddingResult.Success(reply.vectors());
		}
		return new EmbeddingResult.Failed(outcome == LlmCallOutcome.INVALID_OUTPUT
			? GenerationOutcome.Failure.INVALID_OUTPUT : reply.failure());
	}

	private static boolean valid(List<List<Float>> vectors, int count) {
		if (vectors == null || vectors.size() != count || vectors.isEmpty()) {
			return false;
		}
		var first = vectors.getFirst();
		if (first == null || first.isEmpty()) {
			return false;
		}
		return vectors.stream().allMatch(vector -> vector != null && vector.size() == first.size()
			&& vector.stream().allMatch(value -> value != null && Float.isFinite(value)));
	}

	private void finish(long id, LlmCallOutcome outcome, Integer inputTokens, String model, long started) {
		int duration = (int)Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
		var usage = inputTokens == null ? null : new ChatProvider.Usage(inputTokens, 0, 0);
		llmCallWriter.finish(id, outcome, inputTokens, null, prices.get(model).cost(usage), duration);
	}
}
