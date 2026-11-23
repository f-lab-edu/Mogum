package com.mogum.job;

import org.springframework.stereotype.Component;

import com.mogum.course.service.LessonAccessService;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 주인은 받기 틀이 먼저 확인한다. 자료 유무와 무관하게 강의를 열 수 있는지만 확인한다. */
@Component
@RequiredArgsConstructor
public final class MaterialIntakeHandler implements JobIntakeHandler {
	private final LessonAccessService lessonAccessService;

	@Override
	public JobType type() {
		return JobType.MATERIAL;
	}

	@Override
	public void check(long userId, JobRequest request) {
		lessonAccessService.open(userId, request.targetId());
	}
}
