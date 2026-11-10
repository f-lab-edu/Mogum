package com.mogum.card.domain;

public final class CardConceptFixtures {
    private CardConceptFixtures() { }

    public static CardConcept link(Card card, Concept concept) {
        return CardConcept.link(card, concept);
    }
}
