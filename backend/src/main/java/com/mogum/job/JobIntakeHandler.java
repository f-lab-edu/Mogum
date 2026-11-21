package com.mogum.job;

import com.mogum.job.domain.JobType;

public interface JobIntakeHandler {
    JobType type();

    /**
     * 대상이 지금 이 만들기를 받을 수 있는지 확인한다. 주인은 틀이 먼저 확인하며, 거절은 ApiException이다.
     * 첫 로드맵 코스는 여기서 로드맵을 진행 중으로 바꾼다. 뒤의 건 수 · 크레딧 거절도 같은 받기 트랜잭션에서 롤백한다.
     */
    void check(long userId, JobRequest request);
}
