package com.mogum.job;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자 행을 잠근 받기 트랜잭션에서 순서를 확인하고 첫 코스의 로드맵을 시작한다. */
@Component
@RequiredArgsConstructor
public final class PlanRoadmapCourseIntakeHandler implements JobIntakeHandler {
    private final CourseRepository courseRepository;
    private final GenerationJobRepository generationJobRepository;

    @Override
    public JobType type() { return JobType.PLAN_ROADMAP_COURSE; }

    @Override
    public void check(long userId, JobRequest request) {
        var course = courseRepository.findById(request.targetId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var roadmap = course.getRoadmap();
        if (roadmap == null) throw new ApiException(ErrorCode.NOT_FOUND);
        if (course.getStatus() != CourseStatus.NOT_PLANNED) {
            throw new ApiException(ErrorCode.COURSE_PLAN_NOT_ALLOWED);
        }

        int position = course.getPosition();
        if (roadmap.getStatus() == RoadmapStatus.COMPLETED
                || (position > 1 && roadmap.getStatus() != RoadmapStatus.IN_PROGRESS)) {
            throw new ApiException(ErrorCode.COURSE_PLAN_NOT_ALLOWED);
        }

        var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId());
        if (courses.stream().anyMatch(previous -> previous.getPosition() < position
                && previous.getStatus() == CourseStatus.NOT_PLANNED)) {
            throw new ApiException(ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
        }

        if (position == 1) {
            if (generationJobRepository.findSameTarget(userId, JobType.REPLAN_ROADMAP, roadmap.getId(), false)
                    .isPresent()) {
                throw new ApiException(ErrorCode.REPLAN_IN_PROGRESS);
            }
            if (roadmap.getStatus() == RoadmapStatus.REVIEWING) roadmap.start();
        } else {
            var previous = courses.stream()
                    .filter(candidate -> candidate.getPosition() == position - 1)
                    .findFirst();
            if (previous.isEmpty() || previous.get().getStatus() != CourseStatus.COMPLETED) {
                throw new ApiException(ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
            }
        }
    }
}
