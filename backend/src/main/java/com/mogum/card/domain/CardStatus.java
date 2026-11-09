package com.mogum.card.domain;

public enum CardStatus {
    /** 쓰는 중 — 강의에 달려 있다 — 학습 완료 뒤(due_at 이 차면) 복습에 나온다 */
    ACTIVE,
    /** 코스 지워짐 — 코스를 지웠다 — 복습 · 화면에 나오지 않고 숙련도 계산에만 쓴다. lesson_id 가 비어 있다 */
    COURSE_DELETED
}
