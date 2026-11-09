package com.mogum.course.domain;

public enum RoadmapStatus {
    /** 검토 중 — 로드맵 계획이 나왔다 — 이대로 시작 · 다시 짜기 · 계획의 코스 제목 고치기 */
    REVIEWING,
    /** 진행 중 — 「이대로 시작」 — 첫 코스의 작업을 받았다 */
    IN_PROGRESS,
    /** 다 마침 — 마지막 코스를 다 마쳤다 */
    COMPLETED
}

