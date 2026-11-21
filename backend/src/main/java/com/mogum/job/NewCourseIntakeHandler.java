package com.mogum.job;

import org.springframework.stereotype.Component;

import com.mogum.job.domain.JobType;

@Component
public final class NewCourseIntakeHandler implements JobIntakeHandler {
	@Override
	public JobType type() {
		return JobType.NEW_COURSE;
	}

	@Override
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	public void check(long userId, JobRequest request) {}
	// @checkstyle:on
}
