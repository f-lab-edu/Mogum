package com.mogum.generation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiFailuresTest {
	@Test
	@DisplayName("SDK 데이터 예외에 감싸진 시간 초과도 원인 타입으로 찾는다")
	void recognizesWrappedTimeout() {
		// given
		var exception = new RuntimeException(new InterruptedIOException());

		// when
		var result = OpenAiFailures.timedOut(exception);

		// then
		assertThat(result).isTrue();
		assertThat(OpenAiFailures.timedOut(new RuntimeException())).isFalse();
	}
}
