package com.mogum.generation;

import lombok.extern.slf4j.Slf4j;

/** 생성 처리기의 예상 밖 예외를 원문 없이 기록하고 다시 시도 가능한 실패로 돌린다. */
@Slf4j
final class GenerationFailures {
    private GenerationFailures() { }

    static GenerationOutcome.Failure unavailable(RuntimeException exception) {
        // 메시지·cause·suppressed에는 입력이나 생성 원문이 섞일 수 있다.
        var safe = new RuntimeException(exception.getClass().getName());
        safe.setStackTrace(exception.getStackTrace());
        log.error("job generate failed", safe);
        return GenerationOutcome.Failure.PROVIDER_UNAVAILABLE;
    }
}
