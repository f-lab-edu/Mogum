package com.mogum.course.repository;

import com.mogum.course.domain.Course;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashMap;
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
}
