package com.mogum.job.repository;

import com.mogum.job.domain.GenerationJob;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface GenerationJobRepository extends Repository<GenerationJob, Long> {
    <S extends GenerationJob> S save(S entity);

    Optional<GenerationJob> findById(Long id);
}
