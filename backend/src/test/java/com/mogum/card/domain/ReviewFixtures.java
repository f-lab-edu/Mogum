package com.mogum.card.domain;

public final class ReviewFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private ReviewFixtures() {}
	// @checkstyle:on

	public static Review review(Card card, Rating rating) {
		return Review.record(card, rating);
	}
}
