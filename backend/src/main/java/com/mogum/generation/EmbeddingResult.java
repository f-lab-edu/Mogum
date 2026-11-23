package com.mogum.generation;

import java.util.List;

/** 입력 순서의 벡터 목록 또는 워커가 다룰 실패. */
public sealed interface EmbeddingResult {
	record Success(List<List<Float>> value) implements EmbeddingResult {
		public Success {
			value = value.stream().map(List::copyOf).toList();
		}
	}

	record Failed(GenerationOutcome.Failure failure) implements EmbeddingResult {
	}
}
