package com.mogum.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;

public interface CourseRepository extends Repository<Course, Long> {
	<S extends Course> S save(S entity);

	Optional<Course> findById(Long id);

	List<Course> findByRoadmapIdOrderByPositionAsc(Long roadmapId);

	List<Course> findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(Long userId, CourseStatus status);
}
