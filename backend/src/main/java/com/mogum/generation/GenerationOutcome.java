package com.mogum.generation;

/** 이어 쓰기는 generate 내부에서 끝내며 워커에는 성공 또는 네 실패만 돌려준다. */
public sealed interface GenerationOutcome {
    record Success(GeneratedResult result) implements GenerationOutcome { }

    enum Failure implements GenerationOutcome {
        /** 연결 오류 · 시간 초과 · 몰림 */
        PROVIDER_UNAVAILABLE,

        /** 형식 검사 불합격 · 이어 써도 끝나지 않음 */
        INVALID_OUTPUT,

        /** 키 · 쿼터 같은 설정 문제 */
        PROVIDER_REJECTED,

        /** 입력이 너무 크다 */
        INPUT_TOO_LARGE
    }
}
