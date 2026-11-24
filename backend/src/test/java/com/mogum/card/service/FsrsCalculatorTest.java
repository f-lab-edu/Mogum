package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;

class FsrsCalculatorTest {
	private static final Instant NOW = Instant.parse("2026-10-12T12:34:56Z");
	private final FsrsCalculator fsrsCalculator = new FsrsCalculator();

	@ParameterizedTest(name = "평가 {0} 간격 {1}분 단계 {2}")
	@CsvSource({"AGAIN, 1, 0", "GOOD, 10, 1"})
	@DisplayName("새 카드는 기본 1분과 10분 학습 단계를 따른다")
	void schedulesDefaultLearningSteps(Rating rating, long minutes, int step) {
		// given
		var card = CardFixtures.scheduled(1L, NOW.minusSeconds(30));

		// when
		var actual = fsrsCalculator.review(card, rating, NOW);

		// then
		assertThat(actual.fsrsState()).isEqualTo(FsrsState.LEARNING);
		assertThat(actual.fsrsStep()).isEqualTo(step);
		assertThat(actual.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(minutes)));
		assertThat(actual.lastReviewedAt()).isEqualTo(NOW);
		assertThat(actual.stability()).isPositive();
		assertThat(actual.difficulty()).isBetween(1.0, 10.0);
	}

	@Test
	@DisplayName("학습 단계 1에서 좋음이면 단계를 비우고 날 단위 복습으로 간다")
	void graduatesAfterLastLearningStep() {
		// given
		var card = CardFixtures.scheduled(1L, NOW);
		card.applyReview(fsrsCalculator.review(card, Rating.GOOD, NOW));
		var reviewedAt = NOW.plus(Duration.ofMinutes(10));

		// when
		var actual = fsrsCalculator.review(card, Rating.GOOD, reviewedAt);

		// then
		assertThat(actual.fsrsState()).isEqualTo(FsrsState.REVIEW);
		assertThat(actual.fsrsStep()).isNull();
		assertThat(Duration.between(reviewedAt, actual.dueAt())).isGreaterThanOrEqualTo(Duration.ofDays(1));
	}

	@Test
	@DisplayName("새 카드의 쉬움은 바로 날 단위 복습으로 간다")
	void graduatesImmediatelyForEasy() {
		// given
		var card = CardFixtures.scheduled(1L, NOW);

		// when
		var actual = fsrsCalculator.review(card, Rating.EASY, NOW);

		// then
		assertThat(actual.fsrsState()).isEqualTo(FsrsState.REVIEW);
		assertThat(actual.fsrsStep()).isNull();
		assertThat(Duration.between(NOW, actual.dueAt())).isGreaterThanOrEqualTo(Duration.ofDays(1));
	}

	@Test
	@DisplayName("복습 중 다시는 10분 재학습 단계로 간다")
	void relearnsAfterAgainInReview() {
		// given
		var card = reviewCard(1L);

		// when
		var actual = fsrsCalculator.review(card, Rating.AGAIN, NOW);

		// then
		assertThat(actual.fsrsState()).isEqualTo(FsrsState.RELEARNING);
		assertThat(actual.fsrsStep()).isZero();
		assertThat(actual.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("10분 재학습 뒤 좋음이면 단계를 비우고 복습 중으로 돌아간다")
	void graduatesAfterRelearning() {
		// given
		var card = reviewCard(1L);
		card.applyReview(fsrsCalculator.review(card, Rating.AGAIN, NOW));
		var reviewedAt = NOW.plus(Duration.ofMinutes(10));

		// when
		var actual = fsrsCalculator.review(card, Rating.GOOD, reviewedAt);

		// then
		assertThat(actual.fsrsState()).isEqualTo(FsrsState.REVIEW);
		assertThat(actual.fsrsStep()).isNull();
		assertThat(Duration.between(reviewedAt, actual.dueAt())).isGreaterThanOrEqualTo(Duration.ofDays(1));
	}

	@ParameterizedTest(name = "평가 {0}")
	@EnumSource(Rating.class)
	@DisplayName("학습 카드의 미리 계산과 실제 평가는 네 등급 모두 같은 간격이다")
	void previewsSameLearningIntervalsAsReview(Rating rating) {
		// given
		var card = CardFixtures.scheduled(1L, NOW);
		var expected = Duration.between(NOW, fsrsCalculator.review(card, rating, NOW).dueAt());

		// when
		var actual = fsrsCalculator.preview(card, NOW);

		// then
		assertThat(actual).hasSize(4).containsEntry(rating, expected);
		assertThat(card.getFsrsState()).isEqualTo(FsrsState.LEARNING);
		assertThat(card.getFsrsStep()).isZero();
		assertThat(card.getStability()).isNull();
		assertThat(card.getDifficulty()).isNull();
		assertThat(card.getDueAt()).isEqualTo(NOW);
		assertThat(card.getLastReviewedAt()).isNull();
	}

	@ParameterizedTest(name = "평가 {0}")
	@EnumSource(Rating.class)
	@DisplayName("흔들기가 적용되는 복습 카드도 미리 계산과 실제 평가의 간격이 같다")
	void previewsSameFuzzedIntervalsAsReview(Rating rating) {
		// given
		var card = reviewCard(Long.MAX_VALUE);
		var expected = Duration.between(NOW, fsrsCalculator.review(card, rating, NOW).dueAt());

		// when
		var actual = fsrsCalculator.preview(card, NOW);

		// then
		assertThat(actual).hasSize(4).containsEntry(rating, expected);
		assertThat(actual.get(Rating.GOOD)).isGreaterThan(Duration.ofDays(3));
		assertThat(card.getFsrsState()).isEqualTo(FsrsState.REVIEW);
		assertThat(card.getLastReviewedAt()).isEqualTo(NOW.minus(Duration.ofDays(100)));
		assertThat(card.getStability()).isEqualTo(100.0);
		assertThat(card.getDifficulty()).isEqualTo(5.0);
		assertThat(card.getDueAt()).isEqualTo(NOW);
	}

	@ParameterizedTest(name = "평가 {0}")
	@EnumSource(Rating.class)
	@DisplayName("같은 카드의 저장 상태로 다시 계산하면 중간 호출과 무관하게 같은 결과다")
	void repeatsResultsForSameStoredState(Rating rating) {
		// given
		var card = reviewCard(1L);
		var expected = fsrsCalculator.review(card, rating, NOW);
		fsrsCalculator.preview(reviewCard(20L), NOW);

		// when
		var actual = new FsrsCalculator().review(card, rating, NOW);

		// then
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("카드 ID별 씨앗으로 넓은 간격이 모두 같지 않게 흔들린다")
	void fuzzesIntervalsAcrossCards() {
		// when
		var intervals = LongStream.rangeClosed(1, 64)
			.mapToObj(id -> fsrsCalculator.review(reviewCard(id), Rating.GOOD, NOW).dueAt())
			.distinct().toList();

		// then
		assertThat(intervals).hasSizeGreaterThan(1);
	}

	@Test
	@DisplayName("마지막 평가 시각도 씨앗에 반영되어 같은 경과일의 카드 간격이 달라진다")
	void includesLastReviewedAtInSeed() {
		// when
		var intervals = LongStream.rangeClosed(1, 64).mapToObj(seconds -> {
			var card = reviewCard(1L);
			card.applyReview(new FsrsResult(FsrsState.REVIEW, null, 100.0, 5.0, NOW,
				NOW.minus(Duration.ofDays(100)).minusSeconds(seconds)));
			return fsrsCalculator.review(card, Rating.GOOD, NOW).dueAt();
		}).distinct().toList();

		// then
		assertThat(intervals).hasSizeGreaterThan(1);
	}

	@Test
	@DisplayName("다음 볼 때와 마지막 평가 시각은 넘긴 시각을 기준으로 계산한다")
	void usesExplicitReviewInstant() {
		// given
		var card = CardFixtures.scheduled(1L, NOW);
		var reviewedAt = Instant.parse("2001-01-01T01:02:03.123456Z");

		// when
		var actual = fsrsCalculator.review(card, Rating.AGAIN, reviewedAt);

		// then
		assertThat(actual.lastReviewedAt()).isEqualTo(reviewedAt);
		assertThat(actual.dueAt()).isEqualTo(reviewedAt.plusSeconds(60));
	}

	@Test
	@DisplayName("학습 완료 전 카드 평가는 기본 시각을 넣지 않고 즉시 실패한다")
	void rejectsReviewBeforeLessonCompletion() {
		// given
		var card = CardFixtures.withId(1L);

		// when & then
		assertThatThrownBy(() -> fsrsCalculator.review(card, Rating.GOOD, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("학습 완료 전 카드의 미리 계산도 즉시 실패한다")
	void rejectsPreviewBeforeLessonCompletion() {
		// given
		var card = CardFixtures.withId(1L);

		// when & then
		assertThatThrownBy(() -> fsrsCalculator.preview(card, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("평가 시각이 없으면 라이브러리의 시스템 시계로 대체하지 않고 실패한다")
	void rejectsMissingReviewInstant() {
		// given
		var card = CardFixtures.scheduled(1L, NOW);

		// when & then
		assertThatThrownBy(() -> fsrsCalculator.review(card, Rating.GOOD, null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("미리 계산 시각이 없으면 시스템 시계로 대체하지 않고 실패한다")
	void rejectsMissingPreviewInstant() {
		// given
		var card = CardFixtures.scheduled(1L, NOW);

		// when & then
		assertThatThrownBy(() -> fsrsCalculator.preview(card, null))
			.isInstanceOf(NullPointerException.class);
	}

	private static Card reviewCard(long id) {
		var card = CardFixtures.scheduled(id, NOW);
		card.applyReview(new FsrsResult(FsrsState.REVIEW, null, 100.0, 5.0, NOW,
			NOW.minus(Duration.ofDays(100))));
		return card;
	}
}
