package com.mogum.job;

import com.mogum.course.domain.LearningInput;
import com.mogum.job.domain.JobType;

/** 받기의 값. targetId는 종류에 따라 코스 · 로드맵 · 강의 ID이며 새로 배우기는 비어 있다. */
public record JobRequest(JobType type, Long targetId, LearningInput input, String feedback) {
}
