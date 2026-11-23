package com.mogum.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.Lesson;

import jakarta.persistence.LockModeType;

public interface LessonRepository extends Repository<Lesson, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Course c where c.userId = :userId "
		+ "and c.id = (select l.course.id from Lesson l where l.id = :lessonId)")
	Optional<Course> findCourseForUpdate(long userId, long lessonId);

	<S extends Lesson> S save(S entity);

	Optional<Lesson> findById(Long id);

	List<Lesson> findByCourseIdOrderByPositionAsc(Long courseId);
}
