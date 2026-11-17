package com.mogum.generation.call.domain;

public enum LlmCallOutcome {
    /** 성공 */
    SUCCEEDED,

    /** 출력이 잘렸다 — 이어 쓴다 */
    TRUNCATED,

    /** 형식 검사 불합격 */
    INVALID_OUTPUT,

    /** 시간 초과 */
    TIMEOUT,

    /** 몰림(429) */
    RATE_LIMITED,

    /** 프로바이더 · 연결 오류 */
    PROVIDER_ERROR,

    /** 키 · 쿼터 같은, 다시 해도 같은 거절 */
    REJECTED
}
