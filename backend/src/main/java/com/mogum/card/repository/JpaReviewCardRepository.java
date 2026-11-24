package com.mogum.card.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mogum.card.domain.Card;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JpaReviewCardRepository implements ReviewCardRepository {
	private final EntityManager entityManager;

	@Override
	@SuppressWarnings("unchecked")
	public List<Card> findActiveScheduledCards(long userId, Long courseId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		String sql = """
			SELECT c.* FROM cards c
			WHERE c.user_id = :userId AND c.status = 'ACTIVE' AND c.due_at IS NOT NULL
			""";
		// @checkstyle:on
		if (courseId != null) {
			sql += " AND EXISTS (SELECT 1 FROM lessons l WHERE l.id = c.lesson_id AND l.course_id = :courseId)";
		}
		var query = entityManager.createNativeQuery(sql + " ORDER BY c.due_at ASC, c.id ASC", Card.class)
			.setParameter("userId", userId);
		if (courseId != null) {
			query.setParameter("courseId", courseId);
		}
		return query.getResultList();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Optional<String> findOwnedCourseTitle(long userId, long courseId) {
		return entityManager.createNativeQuery(
			"SELECT title FROM courses WHERE id = :courseId AND user_id = :userId", String.class)
			.setParameter("courseId", courseId).setParameter("userId", userId)
			.getResultStream().findFirst();
	}
}
