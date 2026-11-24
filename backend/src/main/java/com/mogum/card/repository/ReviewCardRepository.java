package com.mogum.card.repository;

import java.util.List;
import java.util.Optional;

import com.mogum.card.domain.Card;

public interface ReviewCardRepository {
	List<Card> findActiveScheduledCards(long userId, Long courseId);

	Optional<String> findOwnedCourseTitle(long userId, long courseId);
}
