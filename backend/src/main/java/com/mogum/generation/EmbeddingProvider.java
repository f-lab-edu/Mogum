package com.mogum.generation;

import java.util.List;

import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;

/** 정의 목록을 한 번에 보내는 SDK 없는 HTTP 경계. */
@FunctionalInterface
public interface EmbeddingProvider {
	Reply send(Request request);

	record Request(long jobId, JobType jobType, EmbeddingSettings settings, List<String> definitions) {
		public Request {
			definitions = List.copyOf(definitions);
		}
	}

	record Reply(List<List<Float>> vectors, LlmCallOutcome outcome,
		GenerationOutcome.Failure failure, Integer inputTokens) {
	}
}
