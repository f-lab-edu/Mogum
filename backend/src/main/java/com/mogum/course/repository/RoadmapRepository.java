package com.mogum.course.repository;

import com.mogum.course.domain.Roadmap;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface RoadmapRepository extends Repository<Roadmap, Long> {
    <S extends Roadmap> S save(S entity);

    Optional<Roadmap> findById(Long id);
}

