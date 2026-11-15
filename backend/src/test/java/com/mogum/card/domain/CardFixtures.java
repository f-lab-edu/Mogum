package com.mogum.card.domain;

import org.springframework.test.util.ReflectionTestUtils;

public final class CardFixtures {
    private CardFixtures() { }

    public static Card card(long userId, long lessonId, int position, String question, String answer) {
        return Card.write(userId, lessonId, position, question, answer);
    }

    public static Card withId(long id) {
        var card = card(8L, 13L, 2, "질문", "답");
        ReflectionTestUtils.setField(card, "id", id);
        return card;
    }
}
