package com.mogum.course.repository;

import com.mogum.course.domain.Roadmap;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeRoadmapRepository implements RoadmapRepository {
    private final Map<Long, Roadmap> entities = new LinkedHashMap<>();
    public <S extends Roadmap> S save(S entity) {
        if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) entities.size() + 1);
        entities.put(entity.getId(), entity);
        return entity;
    }
    public Optional<Roadmap> findById(Long id) { return Optional.ofNullable(entities.get(id)); }
}
