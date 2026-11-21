package com.mogum.card.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.card.domain.CardConcept;
import com.mogum.card.domain.CardConceptId;

public interface CardConceptRepository extends Repository<CardConcept, CardConceptId> {
	<S extends CardConcept> S save(S entity);

	Optional<CardConcept> findById(CardConceptId id);
}
