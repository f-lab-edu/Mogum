package com.mogum.card.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.mogum.card.domain.Card;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;

import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

/**
 * 라이브러리 기본값을 유지하며 평가마다 스케줄러를 새로 만든다.
 * 씨앗은 Objects.hash(저장된 카드 ID, lastReviewedAt)를 MurmurHash3의 32비트 finalizer로 섞은 값이다.
 * 아직 평가하지 않은 시각은 hash 0이며, 비슷한 ID나 시각도 넓게 흩어지게 한다.
 * 미리 계산도 등급마다 같은 씨앗에서 시작하므로 실제 평가와 같은 간격을 낸다.
 */
@Service
public class FsrsCalculator {
	public FsrsResult review(Card card, Rating rating, Instant reviewedAt) {
		Objects.requireNonNull(reviewedAt, "reviewedAt");
		if (card.getDueAt() == null) {
			throw new IllegalArgumentException("학습 완료 전 카드는 평가할 수 없습니다.");
		}
		var input = io.github.openspacedrepetition.Card.builder()
			// 라이브러리 ID는 ReviewLog에만 쓰므로 long ID를 좁히지 않고 상수로 둔다.
			.cardId(0)
			.state(State.valueOf(card.getFsrsState().name()))
			.step(card.getFsrsStep())
			.stability(card.getStability())
			.difficulty(card.getDifficulty())
			.due(card.getDueAt())
			.lastReview(card.getLastReviewedAt())
			.build();
		var scheduler = Scheduler.builder().randomSeedNumber(seed(card)).build();
		var result = scheduler.reviewCard(input,
			io.github.openspacedrepetition.Rating.valueOf(rating.name()), reviewedAt).card();
		return new FsrsResult(FsrsState.valueOf(result.getState().name()), result.getStep(),
			result.getStability(), result.getDifficulty(), result.getDue(), result.getLastReview());
	}

	public double retrievability(Card card, Instant now) {
		Objects.requireNonNull(now, "now");
		var input = io.github.openspacedrepetition.Card.builder()
			.cardId(0)
			.state(State.valueOf(card.getFsrsState().name()))
			.step(card.getFsrsStep())
			.stability(card.getStability())
			.difficulty(card.getDifficulty())
			.due(card.getDueAt())
			.lastReview(card.getLastReviewedAt())
			.build();
		return Scheduler.builder().build().getCardRetrievability(input, now);
	}

	public Map<Rating, Duration> preview(Card card, Instant now) {
		var intervals = new EnumMap<Rating, Duration>(Rating.class);
		for (var rating : Rating.values()) {
			intervals.put(rating, Duration.between(now, review(card, rating, now).dueAt()));
		}
		return Map.copyOf(intervals);
	}

	private static int seed(Card card) {
		int hash = Objects.hash(card.getId(), card.getLastReviewedAt());
		hash ^= hash >>> 16;
		hash *= 0x85ebca6b;
		hash ^= hash >>> 13;
		hash *= 0xc2b2ae35;
		return hash ^ (hash >>> 16);
	}
}
