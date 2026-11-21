package com.mogum.card.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.card.domain.Card;

public interface CardRepository extends Repository<Card, Long> {
	<S extends Card> S save(S entity);

	Optional<Card> findById(Long id);
}
