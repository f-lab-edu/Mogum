package com.mogum.job;

import com.mogum.job.domain.JobType;
import org.springframework.stereotype.Component;

@Component
public final class NewCourseIntakeHandler implements JobIntakeHandler {
    @Override
    public JobType type() { return JobType.NEW_COURSE; }

    @Override
    public void check(long userId, JobRequest request) { }
}
