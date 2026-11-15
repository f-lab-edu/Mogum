package com.mogum.job.repository;

import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.Optional;

public interface GenerationJobRepository extends Repository<GenerationJob, Long> {
    <S extends GenerationJob> S save(S entity);

    Optional<GenerationJob> findById(Long id);

    @Query("""
            select j from GenerationJob j where j.userId = :userId and j.type = :type
            and (j.courseId = :targetId or j.roadmapId = :targetId or j.lessonId = :targetId)
            and (:includeSucceeded = true or j.status <> com.mogum.job.domain.JobStatus.SUCCEEDED)
            """)
    Optional<GenerationJob> findSameTarget(long userId, JobType type, long targetId, boolean includeSucceeded);

    long countByUserIdAndStatusIn(long userId, Collection<JobStatus> statuses);

    void delete(GenerationJob job);
}
