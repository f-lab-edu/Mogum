package com.mogum.generation;

/** 해석된 값 또는 워커가 다룰 네 실패 중 하나. */
public sealed interface ChatResult<T> {
	record Success<T>(T value) implements ChatResult<T> {
	}

	record Failed<T>(GenerationOutcome.Failure failure) implements ChatResult<T> {
	}
}
