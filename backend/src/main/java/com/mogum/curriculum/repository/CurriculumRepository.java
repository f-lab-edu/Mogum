package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.Curriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CurriculumRepository extends JpaRepository<Curriculum, Long> {
    Optional<Curriculum> findByIdAndUserId(Long id, Long userId);
}
