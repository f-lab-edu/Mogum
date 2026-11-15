package com.mogum.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardTest {
    @Test
    @DisplayName("카드는 주인과 강의 ID 및 순서와 질문과 답을 담고 학습 완료 전 FSRS 값으로 생긴다")
    void writesHiddenLearningCard() {
        // when
        var card = Card.write(8L, 13L, 2, "변수란 무엇인가요?", "값을 담는 이름이에요.");

        // then
        assertThat(card.getId()).isNull();
        assertThat(card.getCreatedAt()).isNull();
        assertThat(card.getUpdatedAt()).isNull();
        assertThat(card.getUserId()).isEqualTo(8L);
        assertThat(card.getLessonId()).isEqualTo(13L);
        assertThat(card.getPosition()).isEqualTo(2);
        assertThat(card.getQuestion()).isEqualTo("변수란 무엇인가요?");
        assertThat(card.getAnswer()).isEqualTo("값을 담는 이름이에요.");
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getFsrsState()).isEqualTo(FsrsState.LEARNING);
        assertThat(card.getFsrsStep()).isZero();
        assertThat(card.getDueAt()).isNull();
        assertThat(card.getStability()).isNull();
        assertThat(card.getDifficulty()).isNull();
        assertThat(card.getLastReviewedAt()).isNull();
    }
}
