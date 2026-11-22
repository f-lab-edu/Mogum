package com.mogum.common.api;

public record FieldViolation(String field, Reason reason) {
	/** 요청 칸의 검증 실패를 공통 오류 응답에 싣는 까닭. */
	public enum Reason {
		/** @Size · @CodePointLength의 최댓값이나 작업 요청 글자 수 상한을 넘었을 때 쓴다. */
		TOO_LONG,

		/** @NotBlank · @NotNull 또는 작업 요청의 필수 값 · 빈 글 제약을 위반한 칸에 쓴다. */
		REQUIRED,

		/** 값 목록에 없는 요청 칸의 값에 쓴다. */
		INVALID_VALUE,

		/** 선택한 종류나 다른 칸의 값에 따라 비어 있어야 하는 칸에 쓴다. */
		NOT_ALLOWED
	}
}
