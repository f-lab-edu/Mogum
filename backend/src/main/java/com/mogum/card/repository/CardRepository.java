package com.mogum.card.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.card.domain.Card;

public interface CardRepository extends Repository<Card, Long> {
	<S extends Card> S save(S entity);

	Optional<Card> findById(Long id);

	@Modifying(flushAutomatically = true)
	@Query("update Card c set c.dueAt = :completedAt, c.updatedAt = :completedAt "
		+ "where c.lessonId = :lessonId and c.status = com.mogum.card.domain.CardStatus.ACTIVE and c.dueAt is null")
	int activateLessonCards(long lessonId, Instant completedAt);
}
