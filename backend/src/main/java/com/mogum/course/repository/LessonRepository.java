package com.mogum.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.course.domain.Lesson;

public interface LessonRepository extends Repository<Lesson, Long> {
	<S extends Lesson> S save(S entity);

	Optional<Lesson> findById(Long id);

	List<Lesson> findByCourseIdOrderByPositionAsc(Long courseId);
}
