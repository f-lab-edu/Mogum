package com.mogum.course.repository;

import com.mogum.course.domain.Material;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface MaterialRepository extends Repository<Material, Long> {
    <S extends Material> S save(S entity);

    Optional<Material> findById(Long id);
}

