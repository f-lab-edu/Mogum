package com.mogum.card.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.domain.Card;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.dto.TodayReviewResponse;
import com.mogum.card.repository.ReviewCardRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.time.SeoulClock;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TodayReviewService {
	private static final Duration LEARN_AHEAD_LIMIT = Duration.ofMinutes(20);

	private final ReviewCardRepository reviewCardRepository;
	private final FsrsCalculator fsrsCalculator;
	private final Clock clock;

	public TodayReviewResponse read(long userId, Long courseId) {
		var now = clock.instant();
		String courseTitle = courseId == null ? null : reviewCardRepository.findOwnedCourseTitle(userId, courseId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		var cards = selectCards(userId, courseId, now).stream().map(card -> {
			var intervals = new EnumMap<Rating, Long>(Rating.class);
			fsrsCalculator.preview(card, now)
				.forEach((rating, duration) -> intervals.put(rating, duration.toSeconds()));
			return new TodayReviewResponse.CardItem(card.getId(), card.getQuestion(), card.getAnswer(), intervals);
		}).toList();
		return new TodayReviewResponse(cards, cards.size(), courseTitle);
	}

	public int count(long userId, Long courseId) {
		return selectCards(userId, courseId, clock.instant()).size();
	}

	/** 목록과 코스 및 홈의 수가 함께 쓰는 선택 규칙. 범위를 거른 뒤에 앞당겨 보기를 판단한다. */
	private List<Card> selectCards(long userId, Long courseId, Instant now) {
		var nextDay = new SeoulClock(Clock.fixed(now, SeoulClock.ZONE)).startOfTomorrow();
		var candidates = reviewCardRepository.findActiveScheduledCards(userId, courseId);
		var due = candidates.stream().filter(card -> card.getFsrsState() == FsrsState.REVIEW
			? card.getDueAt().isBefore(nextDay) : !card.getDueAt().isAfter(now)).toList();
		if (!due.isEmpty()) {
			return due;
		}
		var aheadUntil = now.plus(LEARN_AHEAD_LIMIT);
		return candidates.stream().filter(card -> card.getFsrsState() != FsrsState.REVIEW
			&& !card.getDueAt().isAfter(aheadUntil)).toList();
	}
}
