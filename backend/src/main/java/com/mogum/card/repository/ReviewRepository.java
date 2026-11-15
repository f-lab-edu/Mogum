package com.mogum.card.repository;

import com.mogum.card.domain.Review;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface ReviewRepository extends Repository<Review, Long> {
    <S extends Review> S save(S entity);

    Optional<Review> findById(Long id);
}
