package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.CurriculumStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurriculumStepRepository extends JpaRepository<CurriculumStep, Long> {
}
