package com.mogum.card.domain;

import java.time.Instant;

import org.springframework.test.util.ReflectionTestUtils;

public final class CardFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private CardFixtures() {}
	// @checkstyle:on

	public static Card card(long userId, long lessonId, int position, String question, String answer) {
		return Card.write(userId, lessonId, position, question, answer);
	}

	public static Card withId(long id) {
		var card = card(8L, 13L, 2, "질문", "답");
		ReflectionTestUtils.setField(card, "id", id);
		return card;
	}

	public static Card scheduled(long id, Instant dueAt) {
		var card = withId(id);
		ReflectionTestUtils.setField(card, "dueAt", dueAt);
		return card;
	}
}
