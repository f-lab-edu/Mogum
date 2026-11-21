package com.mogum.card.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record CardConceptId(@Column(name = "card_id") Long cardId,
	@Column(name = "concept_id") Long conceptId) implements Serializable {
}
