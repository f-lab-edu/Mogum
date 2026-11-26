package com.mogum.card.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.card.domain.Card;

import jakarta.persistence.LockModeType;

public interface CardRepository extends Repository<Card, Long> {
	<S extends Card> S save(S entity);

	Optional<Card> findById(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Card c where c.id = :id and c.userId = :userId")
	Optional<Card> findByIdAndUserIdForUpdate(long id, long userId);

	void delete(Card card);

	@Modifying(flushAutomatically = true)
	@Query("update Card c set c.dueAt = :completedAt, c.updatedAt = :completedAt "
		+ "where c.lessonId = :lessonId and c.status = com.mogum.card.domain.CardStatus.ACTIVE and c.dueAt is null")
	int activateLessonCards(long lessonId, Instant completedAt);

	@Modifying(flushAutomatically = true)
	@Query("update Card c set c.status = com.mogum.card.domain.CardStatus.COURSE_DELETED, c.updatedAt = :now "
		+ "where c.lessonId in :lessonIds and c.status = com.mogum.card.domain.CardStatus.ACTIVE")
	int hideLessonCards(List<Long> lessonIds, Instant now);

}
