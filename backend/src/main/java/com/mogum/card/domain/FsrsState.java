package com.mogum.card.domain;

public enum FsrsState {
    /** 처음 배우는 중 — 만들 때의 값 — 분 단위 학습 단계(1분 · 10분)를 돈다 */
    LEARNING,
    /** 복습 중 — 날 단위로 돈다 — fsrs_step 이 비어 있다 */
    REVIEW,
    /** 다시 배우는 중 — 복습 중에 「다시」를 눌렀다 — 10분 학습 단계를 돈 뒤 복습 중으로 */
    RELEARNING
}
