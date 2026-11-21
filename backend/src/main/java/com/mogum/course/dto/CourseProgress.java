package com.mogum.course.dto;

public enum CourseProgress {
	/** 짜기 전 — 강의 목록도 성공하지 않은 만들기 작업도 없다. */
	NOT_PLANNED,

	/** 만드는 중 — 강의 목록 작업이 기다림 또는 만드는 중이다. */
	GENERATING,

	/** 실패 — 강의 목록 작업이 실패했다. */
	FAILED,

	/** 만들어짐 — 코스가 검토 중 · 학습 중 · 다 마침이다. */
	GENERATED
}
