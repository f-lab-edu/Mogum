package com.mogum.course.domain;

import org.springframework.test.util.ReflectionTestUtils;

public final class RoadmapFixtures {
	private RoadmapFixtures() {}

	public static Roadmap roadmap(long userId) {
		return Roadmap.plan(userId, new LearningInput("자바", Level.BEGINNER, null, Purpose.GENERAL, null, null),
			"자바 로드맵", "자바로 프로그램을 만들 수 있어요");
	}

	public static Roadmap roadmap(long userId, LearningInput input, String title, String description) {
		return Roadmap.plan(userId, input, title, description);
	}

	public static Roadmap withStatus(Roadmap roadmap, RoadmapStatus status) {
		if (status == RoadmapStatus.IN_PROGRESS)
			roadmap.start();
		else
			ReflectionTestUtils.setField(roadmap, "status", status);
		return roadmap;
	}
}
