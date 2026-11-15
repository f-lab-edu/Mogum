package com.mogum.card.repository;

import com.mogum.card.domain.CardConcept;
import com.mogum.card.domain.CardConceptId;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CardConceptRepository extends Repository<CardConcept, CardConceptId> {
    <S extends CardConcept> S save(S entity);

    Optional<CardConcept> findById(CardConceptId id);
}
