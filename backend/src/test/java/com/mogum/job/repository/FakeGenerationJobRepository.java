package com.mogum.job.repository;

import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FakeGenerationJobRepository implements GenerationJobRepository {
    private final Map<Long, GenerationJob> jobs = new LinkedHashMap<>();
    private long nextId = 1;

    public <S extends GenerationJob> S save(S entity) {
        if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", nextId++);
        jobs.put(entity.getId(), entity);
        return entity;
    }

    public Optional<GenerationJob> findById(Long id) { return Optional.ofNullable(jobs.get(id)); }

    public Optional<GenerationJob> findSameTarget(long userId, JobType type, long targetId, boolean includeSucceeded) {
        return jobs.values()
                .stream()
                .filter(job -> job.getUserId() == userId && job.getType() == type)
                .filter(job -> Objects.equals(job.getCourseId(), targetId)
                        || Objects.equals(job.getRoadmapId(), targetId)
                        || Objects.equals(job.getLessonId(), targetId))
                .filter(job -> includeSucceeded || job.getStatus() != JobStatus.SUCCEEDED)
                .findFirst();
    }

    public long countByUserIdAndStatusIn(long userId, Collection<JobStatus> statuses) {
        return jobs.values()
                .stream()
                .filter(job -> job.getUserId() == userId && statuses.contains(job.getStatus()))
                .count();
    }

    public void delete(GenerationJob job) { jobs.remove(job.getId()); }

    public int size() { return jobs.size(); }
}
