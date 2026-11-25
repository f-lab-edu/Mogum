package com.mogum.course.dto;

import java.util.List;

public record HomeTodoResponse(List<Item> items) {
	public record Item(Type type, long targetId, String title) {
	}

	public enum Type {
		/** 검토를 기다리는 강의 목록이다. */
		COURSE_REVIEW,
		/** 검토를 기다리는 로드맵 계획이다. */
		ROADMAP_REVIEW,
		/** 다음 코스를 만들 수 있게 된 진행 중 로드맵이다. */
		ROADMAP_NEXT_COURSE
	}
}
