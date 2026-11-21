package com.mogum.card.domain;

import org.springframework.test.util.ReflectionTestUtils;

public final class ConceptFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private ConceptFixtures() {}
	// @checkstyle:on

	public static Concept concept(long userId, String name, String definition, float[] embedding) {
		return Concept.add(userId, name, definition, embedding);
	}

	public static Concept withId(long id) {
		var concept = concept(8L, "변수", "값을 담는 이름", new float[] {1, 2, 3});
		ReflectionTestUtils.setField(concept, "id", id);
		return concept;
	}
}
