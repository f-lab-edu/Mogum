package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.Curriculum;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CurriculumRepository extends Repository<Curriculum, Long> {
    <S extends Curriculum> S save(S entity);

    Optional<Curriculum> findById(Long id);

    Optional<Curriculum> findByIdAndUserId(Long id, Long userId);
}
