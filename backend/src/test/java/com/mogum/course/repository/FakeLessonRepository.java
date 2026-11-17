package com.mogum.course.repository;

import com.mogum.course.domain.Lesson;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeLessonRepository implements LessonRepository {
    private final Map<Long, Lesson> entities = new LinkedHashMap<>();

    public <S extends Lesson> S save(S entity) {
        if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) entities.size() + 1);
        entities.put(entity.getId(), entity);
        return entity;
    }

    public Optional<Lesson> findById(Long id) { return Optional.ofNullable(entities.get(id)); }
}
