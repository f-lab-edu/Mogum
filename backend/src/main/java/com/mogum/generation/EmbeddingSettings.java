package com.mogum.generation;

import java.time.Duration;

/** 임베딩 한 요청의 모델과 시간 제한. 출력 상한과 이어 쓰기는 없다. */
public record EmbeddingSettings(String model, Duration timeout) {
	public EmbeddingSettings {
		if (model == null || model.isBlank() || timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("Invalid embedding settings");
		}
	}
}
