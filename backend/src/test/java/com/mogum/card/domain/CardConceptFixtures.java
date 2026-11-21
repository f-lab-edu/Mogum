package com.mogum.card.domain;

public final class CardConceptFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private CardConceptFixtures() {}
	// @checkstyle:on

	public static CardConcept link(Card card, Concept concept) {
		return CardConcept.link(card, concept);
	}
}
