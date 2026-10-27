package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.CurriculumGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CurriculumGroupRepository extends JpaRepository<CurriculumGroup, Long> {
    Optional<CurriculumGroup> findByIdAndUserId(Long id, Long userId);
}
