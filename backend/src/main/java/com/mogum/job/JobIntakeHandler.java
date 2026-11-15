package com.mogum.job;

import com.mogum.job.domain.JobType;

public interface JobIntakeHandler {
    JobType type();

    /** 대상이 지금 이 만들기를 받을 수 있는지 확인한다. 주인은 틀이 먼저 확인하며, 거절은 ApiException이다. */
    void check(long userId, JobRequest request);
}
