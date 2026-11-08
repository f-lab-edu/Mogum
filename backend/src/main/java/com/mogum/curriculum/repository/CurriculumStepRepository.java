package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.CurriculumStep;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CurriculumStepRepository extends Repository<CurriculumStep, Long> {
    <S extends CurriculumStep> S save(S entity);

    Optional<CurriculumStep> findById(Long id);

    Optional<CurriculumStep> findByIdAndGroup_UserId(Long id, Long userId);
}
