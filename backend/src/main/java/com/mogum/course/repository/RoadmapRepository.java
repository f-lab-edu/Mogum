package com.mogum.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.course.domain.Roadmap;

public interface RoadmapRepository extends Repository<Roadmap, Long> {
	<S extends Roadmap> S save(S entity);

	Optional<Roadmap> findById(Long id);

	List<Roadmap> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
