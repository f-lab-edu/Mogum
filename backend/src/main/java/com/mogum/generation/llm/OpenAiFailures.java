package com.mogum.generation.llm;

import java.io.InterruptedIOException;

import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.openai.errors.OpenAIServiceException;

/** 채팅과 임베딩이 공유하는 프로바이더 실패 분류. 원문은 읽거나 기록하지 않는다. */
final class OpenAiFailures {
	// @checkstyle:off — 포매터가 빈 선언을 한 줄로 유지한다.
	private OpenAiFailures() {}
	// @checkstyle:on

	static boolean timedOut(Throwable exception) {
		for (var cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedIOException) {
				return true;
			}
		}
		return false;
	}

	static Failure rejected(OpenAIServiceException exception) {
		int status = exception.statusCode();
		String code;
		try {
			code = exception.code().orElse("");
		} catch (RuntimeException ignored) {
			// 오류 code의 형식이 깨져도 확인한 HTTP 상태로 분류한다.
			code = "";
		}
		if (status == 400 && code.equals("context_length_exceeded")) {
			return new Failure(LlmCallOutcome.REJECTED, GenerationOutcome.Failure.INPUT_TOO_LARGE);
		}
		if (status == 429 && !code.equals("insufficient_quota")) {
			return new Failure(LlmCallOutcome.RATE_LIMITED, GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		}
		if (status >= 500) {
			return new Failure(LlmCallOutcome.PROVIDER_ERROR, GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		}
		return new Failure(LlmCallOutcome.REJECTED, GenerationOutcome.Failure.PROVIDER_REJECTED);
	}

	record Failure(LlmCallOutcome outcome, GenerationOutcome.Failure failure) {
	}
}
