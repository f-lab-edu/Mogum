package com.mogum.course.repository;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeCourseRepository implements CourseRepository {
    private final Map<Long, Course> entities = new LinkedHashMap<>();

    public <S extends Course> S save(S entity) {
        if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) entities.size() + 1);
        entities.put(entity.getId(), entity);
        return entity;
    }

    public Optional<Course> findById(Long id) { return Optional.ofNullable(entities.get(id)); }

    public List<Course> findByRoadmapIdOrderByPositionAsc(Long roadmapId) {
        return entities.values().stream()
                .filter(entity -> entity.getRoadmap() != null && entity.getRoadmap().getId().equals(roadmapId))
                .sorted(Comparator.comparingInt(Course::getPosition)).toList();
    }

    public List<Course> findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(Long userId, CourseStatus status) {
        return entities.values().stream()
                .filter(course -> course.getUserId().equals(userId) && course.getStatus() != status)
                .sorted(Comparator.comparing(Course::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Course::getId, Comparator.reverseOrder())).toList();
    }
}
