package com.mogum.card.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.card.domain.Review;

public interface ReviewRepository extends Repository<Review, Long> {
	<S extends Review> S save(S entity);

	Optional<Review> findById(Long id);
}
