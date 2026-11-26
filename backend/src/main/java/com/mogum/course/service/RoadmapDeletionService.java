package com.mogum.course.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.JpaCourseDeletionRepository;
import com.mogum.course.repository.RoadmapRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class RoadmapDeletionService {
	private final RoadmapRepository roadmapRepository;
	private final CourseRepository courseRepository;
	private final JpaCourseDeletionRepository jpaCourseDeletionRepository;
	private final CourseDeletionService courseDeletionService;
	private final CreditLedger creditLedger;

	public void delete(long userId, long roadmapId) {
		roadmapRepository.findById(roadmapId).filter(roadmap -> roadmap.getUserId() == userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// 코스별로 잠그면 뒤 코스의 작업을 쥔 워커와 교착된다. 모든 작업 → 사용자 순서다.
		var jobs = jpaCourseDeletionRepository.lockRoadmapJobs(roadmapId);
		creditLedger.lock(userId);
		for (var job : jobs) {
			if (job.unfinished()) {
				creditLedger.restoreIfOwed(userId, job.id());
			}
		}
		jpaCourseDeletionRepository.deleteJobs(jobs.stream().map(job -> job.id()).toList());
		for (var course : courseRepository.findByRoadmapIdOrderByPositionAsc(roadmapId)) {
			courseDeletionService.deleteContentAfterJobs(userId, course.getId());
		}
		jpaCourseDeletionRepository.deleteRoadmap(roadmapId);
	}
}
