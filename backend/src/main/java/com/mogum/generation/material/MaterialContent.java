package com.mogum.generation.material;

import java.util.List;

import com.mogum.course.domain.PlannedConcept;

/** 본문 원문과 읽을 수 있는 카드. 임베딩은 해석 뒤에 한 번에 계산한다. */
public record MaterialContent(String body, List<Card> cards) {
	public MaterialContent {
		cards = List.copyOf(cards);
	}

	public record Card(String question, String answer, List<PlannedConcept> concepts) {
		public Card {
			concepts = List.copyOf(concepts);
		}
	}
}
