package com.mogum.job;

import org.springframework.stereotype.Component;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 소유권을 확인하고 사용자 행을 잠근 받기 트랜잭션에서 검토 중인지 확인한다. */
@Component
@RequiredArgsConstructor
public final class ReplanRoadmapIntakeHandler implements JobIntakeHandler {
	private final RoadmapRepository roadmapRepository;

	@Override
	public JobType type() {
		return JobType.REPLAN_ROADMAP;
	}

	@Override
	public void check(long userId, JobRequest request) {
		var target = roadmapRepository.findById(request.targetId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (target.getStatus() != RoadmapStatus.REVIEWING) {
			throw new ApiException(ErrorCode.REPLAN_NOT_ALLOWED);
		}
	}
}
