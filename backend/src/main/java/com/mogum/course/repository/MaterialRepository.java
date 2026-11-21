package com.mogum.course.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.course.domain.Material;

public interface MaterialRepository extends Repository<Material, Long> {
	<S extends Material> S save(S entity);

	Optional<Material> findById(Long id);
}
