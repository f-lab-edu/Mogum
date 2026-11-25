package com.mogum.card.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StreakActivityRepository {
	private final EntityManager entityManager;

	@SuppressWarnings("unchecked")
	public List<LocalDate> findFilledDays(long userId, LocalDate today) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		return entityManager.createNativeQuery("""
			SELECT filled_day FROM (
			    SELECT CAST((r.created_at AT TIME ZONE 'Asia/Seoul') - INTERVAL '4 hours' AS date) AS filled_day
			    FROM reviews r JOIN cards c ON c.id = r.card_id
			    WHERE c.user_id = :userId
			    UNION
			    SELECT CAST((l.completed_at AT TIME ZONE 'Asia/Seoul') - INTERVAL '4 hours' AS date) AS filled_day
			    FROM lessons l JOIN courses c ON c.id = l.course_id
			    WHERE c.user_id = :userId AND l.completed_at IS NOT NULL
			) activity
			WHERE filled_day <= :today
			ORDER BY filled_day DESC
			""", LocalDate.class)
			.setParameter("userId", userId)
			.setParameter("today", today)
			.getResultList();
		// @checkstyle:on
	}
}
