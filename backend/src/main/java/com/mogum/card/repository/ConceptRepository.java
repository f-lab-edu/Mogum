package com.mogum.card.repository;

import com.mogum.card.domain.Concept;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface ConceptRepository extends Repository<Concept, Long> {
    <S extends Concept> S save(S entity);

    Optional<Concept> findById(Long id);
}
