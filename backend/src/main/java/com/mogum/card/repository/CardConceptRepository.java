package com.mogum.card.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.card.domain.CardConcept;
import com.mogum.card.domain.CardConceptId;

public interface CardConceptRepository extends Repository<CardConcept, CardConceptId> {
	<S extends CardConcept> S save(S entity);

	Optional<CardConcept> findById(CardConceptId id);

	@Query("select cc from CardConcept cc join fetch cc.card c "
		+ "where cc.id.conceptId in :conceptIds and cc.concept.userId = :userId and c.userId = :userId "
		+ "and exists (select r.id from Review r where r.card = c) "
		+ "order by cc.id.conceptId, c.id")
	List<CardConcept> findReviewedByConceptIds(long userId, Collection<Long> conceptIds);

	@Modifying(flushAutomatically = true)
	@Query("delete from CardConcept c where c.id.cardId = :cardId")
	int deleteByCardId(long cardId);
}
