package com.mogum.card.domain;

public final class ReviewFixtures {
    private ReviewFixtures() { }

    public static Review review(Card card, Rating rating) {
        return Review.record(card, rating);
    }
}
