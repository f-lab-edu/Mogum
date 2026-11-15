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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class JobIntakeService {
    private static final Logger log = LoggerFactory.getLogger(JobIntakeService.class);
    private final GenerationJobRepository jobs;
    private final CourseRepository courses;
    private final RoadmapRepository roadmaps;
    private final LessonRepository lessons;
    private final CreditLedger ledger;
    private final CreditProperties credits;
    private final JobProperties properties;
    private final Map<JobType, JobIntakeHandler> handlers;

    public JobIntakeService(GenerationJobRepository jobs, CourseRepository courses, RoadmapRepository roadmaps,
                            LessonRepository lessons, CreditLedger ledger, CreditProperties credits,
                            JobProperties properties, List<JobIntakeHandler> handlers) {
        this.jobs = jobs;
        this.courses = courses;
        this.roadmaps = roadmaps;
        this.lessons = lessons;
        this.ledger = ledger;
        this.credits = credits;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobIntakeHandler::type, Function.identity()));
    }

    public JobResult receive(long userId, JobRequest request) {
        ledger.lock(userId);
        checkOwner(userId, request);
        var handler = handlers.get(request.type());
        if (handler == null) throw new ApiException(ErrorCode.INVALID_REQUEST);
        handler.check(userId, request);
        if (request.type() != JobType.NEW_COURSE && request.type() != JobType.NEW_ROADMAP) {
            boolean includeSucceeded = request.type() == JobType.MATERIAL || request.type() == JobType.PLAN_ROADMAP_COURSE;
            var existing = jobs.findSameTarget(userId, request.type(), request.targetId(), includeSucceeded);
            if (existing.isPresent()) return result(existing.get());
        }
        checkLimit(userId);
        var job = jobs.save(create(userId, request));
        ledger.consume(userId, job.getId(), cost(job.getType()));
        logAfterCommit(job.getId(), job.getType(), false);
        return result(job);
    }

    public JobResult retry(long userId, long jobId) {
        ledger.lock(userId);
        var job = ownedJob(userId, jobId);
        if (job.getStatus() != JobStatus.FAILED) return result(job);
        if (job.getErrorType() == JobErrorType.INPUT_TOO_LARGE) throw new ApiException(ErrorCode.JOB_RETRY_NOT_ALLOWED);
        checkLimit(userId);
        job.retry();
        ledger.consume(userId, jobId, cost(job.getType()));
        logAfterCommit(jobId, job.getType(), true);
        return result(job);
    }

    public void discard(long userId, long jobId) {
        ledger.lock(userId);
        var job = ownedJob(userId, jobId);
        if (job.getStatus() != JobStatus.FAILED) throw new ApiException(ErrorCode.JOB_DISCARD_NOT_ALLOWED);
        ledger.restoreIfOwed(userId, jobId);
        jobs.delete(job);
    }

    private void checkOwner(long userId, JobRequest request) {
        long owner = switch (request.type()) {
            case NEW_COURSE, NEW_ROADMAP -> userId;
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courses.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getUserId();
            case REPLAN_ROADMAP -> roadmaps.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getUserId();
            case MATERIAL -> lessons.findById(request.targetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getCourse().getUserId();
        };
        if (owner != userId) throw new ApiException(ErrorCode.NOT_FOUND);
    }

    private GenerationJob ownedJob(long userId, long jobId) {
        return jobs.findById(jobId).filter(job -> job.getUserId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void checkLimit(long userId) {
        long count = jobs.countByUserIdAndStatusIn(userId, List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        if (count >= properties.maxInProgressPerUser()) throw new ApiException(ErrorCode.TOO_MANY_IN_PROGRESS);
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
            case NEW_COURSE -> credits.newCourse();
            case NEW_ROADMAP -> credits.newRoadmap();
            case PLAN_ROADMAP_COURSE -> credits.planRoadmapCourse();
            case REPLAN_COURSE -> credits.replanCourse();
            case REPLAN_ROADMAP -> credits.replanRoadmap();
            case MATERIAL -> credits.material();
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
