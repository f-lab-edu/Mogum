package com.mogum.generation;

import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.JobType;

/** 꺼낸 한 시도의 값. targetId는 종류에 따라 코스·로드맵·강의 ID이고 NEW_*는 비어 있다. */
public record JobSnapshot(long id, long userId, JobType type, Long targetId, Input input,
        String feedback, int claimCount) {
    public record Input(String topic, Level level, String levelOther, Purpose purpose,
            String purposeOther, String memo) { }
}
