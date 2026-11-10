package com.mogum.card.repository;

import com.mogum.card.domain.Card;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CardRepository extends Repository<Card, Long> {
    <S extends Card> S save(S entity);

    Optional<Card> findById(Long id);
}
