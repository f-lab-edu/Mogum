package com.mogum.card.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.card.domain.Concept;

public interface ConceptRepository extends Repository<Concept, Long> {
	<S extends Concept> S save(S entity);

	Optional<Concept> findById(Long id);
}
