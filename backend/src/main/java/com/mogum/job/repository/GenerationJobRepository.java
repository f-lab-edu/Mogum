package com.mogum.job.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;

public interface GenerationJobRepository extends Repository<GenerationJob, Long> {
	<S extends GenerationJob> S save(S entity);

	Optional<GenerationJob> findById(Long id);

	List<GenerationJob> findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(long userId, Collection<JobStatus> statuses);

	@Query("""
		select j from GenerationJob j where j.userId = :userId and j.type = :type
		and (j.courseId = :targetId or j.roadmapId = :targetId or j.lessonId = :targetId)
		and (:includeSucceeded = true or j.status <> com.mogum.job.domain.JobStatus.SUCCEEDED)
		""")
	Optional<GenerationJob> findSameTarget(long userId, JobType type, long targetId, boolean includeSucceeded);

	long countByUserIdAndStatusIn(long userId, Collection<JobStatus> statuses);

	void delete(GenerationJob job);
}
