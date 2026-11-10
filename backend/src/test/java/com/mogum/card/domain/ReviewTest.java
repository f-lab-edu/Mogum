package com.mogum.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTest {
    @ParameterizedTest(name = "평가 {0}")
    @EnumSource(Rating.class)
    @DisplayName("복습 기록은 카드와 평가를 담고 카드의 FSRS 상태는 바꾸지 않는다")
    void recordsRatingWithoutChangingCard(Rating rating) {
        // given
        var card = CardFixtures.card(8L, 13L, 2, "질문", "답");

        // when
        var review = Review.record(card, rating);

        // then
        assertThat(review.getId()).isNull();
        assertThat(review.getCreatedAt()).isNull();
        assertThat(review.getUpdatedAt()).isNull();
        assertThat(review.getCard()).isSameAs(card);
        assertThat(review.getRating()).isEqualTo(rating);
        assertThat(card.getFsrsState()).isEqualTo(FsrsState.LEARNING);
        assertThat(card.getFsrsStep()).isZero();
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getDueAt()).isNull();
        assertThat(card.getStability()).isNull();
        assertThat(card.getDifficulty()).isNull();
        assertThat(card.getLastReviewedAt()).isNull();
    }
}
