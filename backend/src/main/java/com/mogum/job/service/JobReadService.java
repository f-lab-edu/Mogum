package com.mogum.job.service;

import com.mogum.common.api.ApiError;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.dto.JobResponse;
import com.mogum.job.repository.GenerationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JobReadService {
    private final GenerationJobRepository jobs;
    private final CourseRepository courses;
    private final RoadmapRepository roadmaps;
    private final LessonRepository lessons;

    public JobReadService(GenerationJobRepository jobs, CourseRepository courses,
                          RoadmapRepository roadmaps, LessonRepository lessons) {
        this.jobs = jobs;
        this.courses = courses;
        this.roadmaps = roadmaps;
        this.lessons = lessons;
    }

    public JobResponse read(long userId, long jobId) {
        var job = jobs.findById(jobId).filter(value -> value.getUserId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var subject = subject(job);
        var result = job.getStatus() == JobStatus.SUCCEEDED ? result(job) : null;
        ApiError error = null;
        if (job.getStatus() == JobStatus.FAILED) {
            var code = job.getErrorType() == JobErrorType.INPUT_TOO_LARGE
                    ? ErrorCode.JOB_INPUT_FAILURE : ErrorCode.JOB_TEMPORARY_FAILURE;
            error = new ApiError(code.name(), code.message(), null, null);
        }
        return new JobResponse(job.getId(), job.getType(), job.getStatus(), subject, result, error);
    }

    private JobResponse.Subject subject(GenerationJob job) {
        if (job.getType() == JobType.NEW_COURSE || job.getType() == JobType.NEW_ROADMAP) {
            var input = job.getInput();
            return new JobResponse.Subject(input.getTopic(), input.getLevel().name(), input.getLevelOther(),
                    input.getPurpose().name(), input.getPurposeOther(), null);
        }
        String title = switch (job.getType()) {
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courses.findById(job.getCourseId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getTitle();
            case REPLAN_ROADMAP -> roadmaps.findById(job.getRoadmapId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getTitle();
            case MATERIAL -> lessons.findById(job.getLessonId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getTitle();
            default -> throw new IllegalStateException("Unsupported job type");
        };
        return new JobResponse.Subject(null, null, null, null, null, title);
    }

    private JobResponse.Result result(GenerationJob job) {
        return switch (job.getType()) {
            case NEW_COURSE, PLAN_ROADMAP_COURSE, REPLAN_COURSE -> new JobResponse.Result("COURSE", job.getCourseId(), null);
            case NEW_ROADMAP, REPLAN_ROADMAP -> new JobResponse.Result("ROADMAP", job.getRoadmapId(), null);
            case MATERIAL -> new JobResponse.Result("LESSON", job.getLessonId(), lessons.findById(job.getLessonId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getCourse().getId());
        };
    }
}
