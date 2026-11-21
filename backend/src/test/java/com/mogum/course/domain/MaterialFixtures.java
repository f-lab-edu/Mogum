package com.mogum.course.domain;

public final class MaterialFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private MaterialFixtures() {}
	// @checkstyle:on

	public static Material material(Lesson lesson, String body) {
		return Material.write(lesson, body);
	}
}
