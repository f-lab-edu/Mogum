package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.CurriculumGroup;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CurriculumGroupRepository extends Repository<CurriculumGroup, Long> {
    <S extends CurriculumGroup> S save(S entity);

    Optional<CurriculumGroup> findById(Long id);

    Optional<CurriculumGroup> findByIdAndUserId(Long id, Long userId);
}
