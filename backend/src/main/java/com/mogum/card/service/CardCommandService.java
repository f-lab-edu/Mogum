package com.mogum.card.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardStatus;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.Review;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.time.SeoulClock;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class CardCommandService {

	private final CardRepository cardRepository;
	private final ReviewRepository reviewRepository;
	private final CardConceptRepository cardConceptRepository;
	private final FsrsCalculator fsrsCalculator;
	private final Clock clock;

	public void review(long userId, long cardId, Rating rating) {
		var card = lockVisibleCard(userId, cardId);
		var now = clock.instant();
		var limit = card.getFsrsState() == FsrsState.REVIEW
			? new SeoulClock(Clock.fixed(now, SeoulClock.ZONE)).startOfTomorrow()
			: now.plus(TodayReviewService.LEARN_AHEAD_LIMIT);
		boolean allowed = card.getFsrsState() == FsrsState.REVIEW
			? card.getDueAt().isBefore(limit) : !card.getDueAt().isAfter(limit);
		if (!allowed) {
			throw new ApiException(ErrorCode.CARD_REVIEW_NOT_ALLOWED);
		}
		card.applyReview(fsrsCalculator.review(card, rating, now));
		reviewRepository.save(Review.record(card, rating));
	}

	public void delete(long userId, long cardId) {
		var card = lockVisibleCard(userId, cardId);
		reviewRepository.deleteByCardId(cardId);
		cardConceptRepository.deleteByCardId(cardId);
		cardRepository.delete(card);
	}

	private Card lockVisibleCard(long userId, long cardId) {
		var card = cardRepository.findByIdAndUserIdForUpdate(cardId, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (card.getStatus() != CardStatus.ACTIVE || card.getDueAt() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return card;
	}
}
