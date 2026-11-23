package com.mogum.course.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

public final class CourseFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private CourseFixtures() {}
	// @checkstyle:on

	public static Course single(long userId) {
		return Course.single(userId, new LearningInput("자바", Level.BEGINNER, null, Purpose.GENERAL, null, null),
			"자바 기초");
	}

	public static Course single(long userId, LearningInput input, String title) {
		return Course.single(userId, input, title);
	}

	public static Course inRoadmap(Roadmap roadmap, int position, String title, String planDescription,
		List<PlannedConcept> planConcepts) {
		return Course.inRoadmap(roadmap, position, title, planDescription, planConcepts);
	}

	public static Course withStatus(Course course, CourseStatus status) {
		if (status != CourseStatus.NOT_PLANNED && course.getStatus() == CourseStatus.NOT_PLANNED) {
			course.planLessons();
		}
		if ((status == CourseStatus.LEARNING || status == CourseStatus.COMPLETED)
			&& course.getStatus() == CourseStatus.REVIEWING) {
			course.start(Instant.parse("2026-11-01T00:00:00Z"));
		}
		if (status == CourseStatus.COMPLETED) {
			course.complete(Instant.parse("2026-11-02T00:00:00Z"));
		} else if (course.getStatus() != status) {
			ReflectionTestUtils.setField(course, "status", status);
		}
		return course;
	}
}
