package com.mogum.job;

import org.springframework.stereotype.Component;

import com.mogum.job.domain.JobType;

@Component
public final class NewRoadmapIntakeHandler implements JobIntakeHandler {
	@Override
	public JobType type() {
		return JobType.NEW_ROADMAP;
	}

	@Override
	public void check(long userId, JobRequest request) {}
}
