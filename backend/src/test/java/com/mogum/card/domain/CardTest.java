package com.mogum.card.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

	@ParameterizedTest(name = "FSRS 상태 {0}")
	@EnumSource(FsrsState.class)
	@DisplayName("평가 결과의 여섯 칸을 한 번에 반영하고 질문과 소속은 보존한다")
	void appliesAllReviewFields(FsrsState state) {
		// given
		var card = CardFixtures.scheduled(1L, Instant.parse("2026-11-23T00:00:00Z"));
		var before = CardFixtures.scheduled(1L, card.getDueAt());
		Integer step = state == FsrsState.REVIEW ? null : 1;
		var dueAt = Instant.parse("2026-11-25T01:02:03Z");
		var reviewedAt = Instant.parse("2026-11-24T01:02:03Z");
		var result = new FsrsResult(state, step, 21.5, 4.2, dueAt, reviewedAt);

		// when
		card.applyReview(result);

		// then
		assertThat(card.getFsrsState()).isEqualTo(state);
		assertThat(card.getFsrsStep()).isEqualTo(step);
		assertThat(card.getStability()).isEqualTo(21.5);
		assertThat(card.getDifficulty()).isEqualTo(4.2);
		assertThat(card.getDueAt()).isEqualTo(dueAt);
		assertThat(card.getLastReviewedAt()).isEqualTo(reviewedAt);
		assertThat(card).usingRecursiveComparison()
			.ignoringFields("fsrsState", "fsrsStep", "stability", "difficulty", "dueAt", "lastReviewedAt")
			.isEqualTo(before);
	}
}
