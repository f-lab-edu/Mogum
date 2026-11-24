package com.mogum.card.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.card.domain.CardConcept;
import com.mogum.card.domain.CardConceptId;

public interface CardConceptRepository extends Repository<CardConcept, CardConceptId> {
	<S extends CardConcept> S save(S entity);

	Optional<CardConcept> findById(CardConceptId id);

	@Modifying(flushAutomatically = true)
	@Query("delete from CardConcept c where c.id.cardId = :cardId")
	int deleteByCardId(long cardId);
}
