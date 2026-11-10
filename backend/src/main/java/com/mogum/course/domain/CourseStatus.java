package com.mogum.course.domain;

public enum CourseStatus {
    /** 짜기 전 — 로드맵의 코스만 — 강의 목록이 아직 없다. 코스 탭에 보이지 않는다 */
    NOT_PLANNED,
    /** 검토 중 — 강의 목록이 나왔다 — 강의는 아직 열 수 없다 */
    REVIEWING,
    /** 학습 중 — 「이대로 시작」했다 */
    LEARNING,
    /** 다 마침 — 모든 강의를 학습 완료했다 — completed_at 이 찬다 */
    COMPLETED
}

