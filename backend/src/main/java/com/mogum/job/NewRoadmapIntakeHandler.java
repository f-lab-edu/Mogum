package com.mogum.job;

import com.mogum.job.domain.JobType;
import org.springframework.stereotype.Component;

@Component
public final class NewRoadmapIntakeHandler implements JobIntakeHandler {
    @Override
    public JobType type() { return JobType.NEW_ROADMAP; }

    @Override
    public void check(long userId, JobRequest request) { }
}
