package com.mogum.job.domain;

public enum JobType {
	/** 새로 배우기 — 코스 */
	NEW_COURSE,

	/** 새로 배우기 — 로드맵 */
	NEW_ROADMAP,

	/** 로드맵의 첫 코스 · 다음 코스의 강의 목록 */
	PLAN_ROADMAP_COURSE,

	/** 다시 짜기 — 코스 */
	REPLAN_COURSE,

	/** 다시 짜기 — 로드맵 계획 */
	REPLAN_ROADMAP,

	/** 강의 자료 + 카드 */
	MATERIAL
}
