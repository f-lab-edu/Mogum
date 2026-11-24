package com.mogum.card.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardStatus;

public class FakeReviewCardRepository implements ReviewCardRepository {
	private final List<Card> cards = new ArrayList<>();
	private final Map<Long, Long> lessonCourseIds = new HashMap<>();
	private final Map<CourseOwner, String> titles = new HashMap<>();

	public void add(Card card, long courseId) {
		cards.add(card);
		lessonCourseIds.put(card.getLessonId(), courseId);
	}

	public void addCourse(long userId, long courseId, String title) {
		titles.put(new CourseOwner(userId, courseId), title);
	}

	@Override
	public List<Card> findActiveScheduledCards(long userId, Long courseId) {
		return cards.stream().filter(card -> card.getUserId() == userId && card.getStatus() == CardStatus.ACTIVE
			&& card.getDueAt() != null
			&& (courseId == null || courseId.equals(lessonCourseIds.get(card.getLessonId()))))
			.sorted(Comparator.comparing(Card::getDueAt).thenComparing(Card::getId)).toList();
	}

	@Override
	public Optional<String> findOwnedCourseTitle(long userId, long courseId) {
		return Optional.ofNullable(titles.get(new CourseOwner(userId, courseId)));
	}

	private record CourseOwner(long userId, long courseId) {
	}
}
