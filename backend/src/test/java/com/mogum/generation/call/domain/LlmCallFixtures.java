package com.mogum.generation.call.domain;

import com.mogum.job.domain.JobType;

public final class LlmCallFixtures {
    private LlmCallFixtures() { }

    public static LlmCall started(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model) {
        return LlmCall.start(jobId, userId, jobType, callType, model);
    }
}
