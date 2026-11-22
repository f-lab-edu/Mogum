package com.mogum.course.repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import com.mogum.course.domain.Lesson;

public final class FakeLessonRepository implements LessonRepository {
	private final Map<Long, Lesson> entities = new LinkedHashMap<>();

	public <S extends Lesson> S save(S entity) {
		if (entity.getId() == null) {
			ReflectionTestUtils.setField(entity, "id", (long)entities.size() + 1);
		}
		entities.put(entity.getId(), entity);
		return entity;
	}

	public Optional<Lesson> findById(Long id) {
		return Optional.ofNullable(entities.get(id));
	}

	public List<Lesson> findByCourseIdOrderByPositionAsc(Long courseId) {
		return entities.values().stream()
			.filter(entity -> entity.getCourse() != null && entity.getCourse().getId().equals(courseId))
			.sorted(Comparator.comparingInt(Lesson::getPosition)).toList();
	}
}
