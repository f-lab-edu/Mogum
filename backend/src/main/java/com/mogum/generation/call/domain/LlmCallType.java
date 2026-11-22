package com.mogum.generation.call.domain;

public enum LlmCallType {
	/** 한 시도의 생성 호출 */
	CHAT,

	/** 잘린 출력을 이어 받는 호출 */
	CONTINUATION,

	/** 임베딩 — 관련 개념 검색 · 같은 개념 판정 */
	EMBEDDING
}
