package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.CurriculumStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CurriculumStepRepository extends JpaRepository<CurriculumStep, Long> {
    Optional<CurriculumStep> findByIdAndGroup_UserId(Long id, Long userId);
}
