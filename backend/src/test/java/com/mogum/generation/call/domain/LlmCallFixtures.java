package com.mogum.generation.call.domain;

import com.mogum.job.domain.JobType;

public final class LlmCallFixtures {
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private LlmCallFixtures() {}
	// @checkstyle:on

	public static LlmCall started(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model) {
		return LlmCall.start(jobId, userId, jobType, callType, model);
	}
}
