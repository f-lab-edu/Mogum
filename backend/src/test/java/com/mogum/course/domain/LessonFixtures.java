package com.mogum.course.domain;

import java.time.Instant;
import java.util.List;

public final class LessonFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private LessonFixtures() {}
	// @checkstyle:on

	public static Lesson withCompletedAt(Lesson lesson, Instant completedAt) {
		lesson.complete(completedAt);
		return lesson;
	}

	public static Lesson lesson(Course course, int position) {
		return Lesson.listed(course, position, "변수", "변수를 배워요", List.of(new PlannedConcept("변수", "값을 담는 이름")));
	}

	public static Lesson lesson(Course course, int position, String title, String description,
		List<PlannedConcept> concepts) {
		return Lesson.listed(course, position, title, description, concepts);
	}
}
