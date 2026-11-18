package com.mogum.job.service;

import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.CreditProperties;
import com.mogum.config.JobProperties;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class JobIntakeService {
    private final GenerationJobRepository generationJobRepository;
    private final CourseRepository courseRepository;
    private final RoadmapRepository roadmapRepository;
    private final LessonRepository lessonRepository;
    private final CreditLedger creditLedger;
    private final CreditProperties creditProperties;
    private final JobProperties jobProperties;
    private final Map<JobType, JobIntakeHandler> handlers;

    public JobIntakeService(
            GenerationJobRepository generationJobRepository,
            CourseRepository courseRepository, RoadmapRepository roadmapRepository, LessonRepository lessonRepository,
            CreditLedger creditLedger, CreditProperties creditProperties, JobProperties jobProperties,
            List<JobIntakeHandler> handlers
    ) {
        this.generationJobRepository = generationJobRepository;
        this.courseRepository = courseRepository;
        this.roadmapRepository = roadmapRepository;
        this.lessonRepository = lessonRepository;
        this.creditLedger = creditLedger;
        this.creditProperties = creditProperties;
        this.jobProperties = jobProperties;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(JobIntakeHandler::type, Function.identity()));
    }

    public JobResult receive(long userId, JobRequest request) {
        creditLedger.lock(userId);
        checkOwner(userId, request);

        var handler = handlers.get(request.type());
        if (handler == null) throw new ApiException(ErrorCode.INVALID_REQUEST);
        handler.check(userId, request);

        if (request.type() != JobType.NEW_COURSE && request.type() != JobType.NEW_ROADMAP) {
            boolean includeSucceeded = request.type() == JobType.MATERIAL
                    || request.type() == JobType.PLAN_ROADMAP_COURSE;
            var existing = generationJobRepository.findSameTarget(
                    userId, request.type(), request.targetId(), includeSucceeded);
            if (existing.isPresent()) return result(existing.get());
        }

        checkLimit(userId);
        var job = generationJobRepository.save(create(userId, request));
        creditLedger.consume(userId, job.getId(), cost(job.getType()));
        logAfterCommit(job.getId(), job.getType(), false);
        return result(job);
    }

    public JobResult retry(long userId, long jobId) {
        creditLedger.lock(userId);
        var job = ownedJob(userId, jobId);
        if (job.getStatus() != JobStatus.FAILED) return result(job);
        if (job.getErrorType() == JobErrorType.INPUT_TOO_LARGE) throw new ApiException(ErrorCode.JOB_RETRY_NOT_ALLOWED);

        checkLimit(userId);
        job.retry();
        creditLedger.consume(userId, jobId, cost(job.getType()));
        logAfterCommit(jobId, job.getType(), true);
        return result(job);
    }

    public void discard(long userId, long jobId) {
        creditLedger.lock(userId);
        var job = ownedJob(userId, jobId);
        if (job.getStatus() != JobStatus.FAILED) throw new ApiException(ErrorCode.JOB_DISCARD_NOT_ALLOWED);

        creditLedger.restoreIfOwed(userId, jobId);
        generationJobRepository.delete(job);
    }

    private void checkOwner(long userId, JobRequest request) {
        long owner = switch (request.type()) {
            case NEW_COURSE, NEW_ROADMAP -> userId;
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courseRepository.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
                    .getUserId();
            case REPLAN_ROADMAP -> roadmapRepository.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
                    .getUserId();
            case MATERIAL -> lessonRepository.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
                    .getCourse()
                    .getUserId();
        };
        if (owner != userId) throw new ApiException(ErrorCode.NOT_FOUND);
    }

    private GenerationJob ownedJob(long userId, long jobId) {
        return generationJobRepository.findById(jobId)
                .filter(job -> job.getUserId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void checkLimit(long userId) {
        long count = generationJobRepository.countByUserIdAndStatusIn(
                userId, List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        if (count >= jobProperties.maxInProgressPerUser()) throw new ApiException(ErrorCode.TOO_MANY_IN_PROGRESS);
    }

    private GenerationJob create(long userId, JobRequest request) {
        return switch (request.type()) {
            case NEW_COURSE -> GenerationJob.newCourse(userId, request.input());
            case NEW_ROADMAP -> GenerationJob.newRoadmap(userId, request.input());
            case PLAN_ROADMAP_COURSE -> GenerationJob.planRoadmapCourse(userId, request.targetId());
            case REPLAN_COURSE -> GenerationJob.replanCourse(userId, request.targetId(), request.feedback());
            case REPLAN_ROADMAP -> GenerationJob.replanRoadmap(userId, request.targetId(), request.feedback());
            case MATERIAL -> GenerationJob.material(userId, request.targetId());
        };
    }

    private int cost(JobType type) {
        return switch (type) {
            case NEW_COURSE -> creditProperties.newCourse();
            case NEW_ROADMAP -> creditProperties.newRoadmap();
            case PLAN_ROADMAP_COURSE -> creditProperties.planRoadmapCourse();
            case REPLAN_COURSE -> creditProperties.replanCourse();
            case REPLAN_ROADMAP -> creditProperties.replanRoadmap();
            case MATERIAL -> creditProperties.material();
        };
    }

    private static JobResult result(GenerationJob job) { return new JobResult(job.getId(), job.getStatus()); }

    /** 커밋된 상태 전환만 남기고 이 호출이 넣은 MDC를 반드시 지운다. */
    private void logAfterCommit(long jobId, JobType type, boolean retried) {
        Runnable write = () -> {
            MDC.put(RequestLogFilter.JOB_ID, Long.toString(jobId));
            try {
                if (retried) log.info("job retried");
                else log.info("job received type={}", type);
            } finally {
                MDC.remove(RequestLogFilter.JOB_ID);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { write.run(); }
            });
        } else {
            write.run();
        }
    }
}
