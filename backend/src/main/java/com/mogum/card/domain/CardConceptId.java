package com.mogum.card.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record CardConceptId(
        @Column(name = "card_id") Long cardId,
        @Column(name = "concept_id") Long conceptId
) implements Serializable {
}
