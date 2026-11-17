package com.mogum.course.repository;

import java.util.List;

/** 작업 도메인의 Java 타입 없이 화면과 시작에 필요한 사실만 읽는다. */
public interface CourseJobRepository {
    List<PlanningJob> findUnfinishedPlanningJobs(long userId, long roadmapId);

    boolean hasUnfinishedReplan(long userId, long courseId);

    record PlanningJob(long courseId, long jobId, boolean failed) { }
}
