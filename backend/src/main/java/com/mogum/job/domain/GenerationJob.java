package com.mogum.job.domain;

import com.mogum.common.persistence.AuditedEntity;
import com.mogum.course.domain.LearningInput;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "generation_jobs")
public class GenerationJob extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private JobStatus status;

    @Embedded
    private LearningInput input;

    @Column(columnDefinition = "text")
    private String feedback;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "roadmap_id")
    private Long roadmapId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", columnDefinition = "text")
    private JobErrorType errorType;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public static GenerationJob newCourse(long userId, LearningInput input) {
        var job = queued(userId, JobType.NEW_COURSE);
        job.input = input;
        return job;
    }

    public static GenerationJob newRoadmap(long userId, LearningInput input) {
        var job = queued(userId, JobType.NEW_ROADMAP);
        job.input = input;
        return job;
    }

    public static GenerationJob planRoadmapCourse(long userId, long courseId) {
        var job = queued(userId, JobType.PLAN_ROADMAP_COURSE);
        job.courseId = courseId;
        return job;
    }

    public static GenerationJob replanCourse(long userId, long courseId, String feedback) {
        var job = queued(userId, JobType.REPLAN_COURSE);
        job.courseId = courseId;
        job.feedback = feedback;
        return job;
    }

    public static GenerationJob replanRoadmap(long userId, long roadmapId, String feedback) {
        var job = queued(userId, JobType.REPLAN_ROADMAP);
        job.roadmapId = roadmapId;
        job.feedback = feedback;
        return job;
    }

    public static GenerationJob material(long userId, long lessonId) {
        var job = queued(userId, JobType.MATERIAL);
        job.lessonId = lessonId;
        return job;
    }

    /** 실패한 작업을 원래 받은 순서를 유지한 채 기다림으로 돌린다. */
    public void retry() {
        if (status != JobStatus.FAILED) throw new IllegalStateException("Only failed jobs can be retried");

        status = JobStatus.QUEUED;
        errorType = null;
        finishedAt = null;
    }

    private static GenerationJob queued(long userId, JobType type) {
        var job = new GenerationJob();
        job.userId = userId;
        job.type = type;
        job.status = JobStatus.QUEUED;
        job.claimCount = 0;
        return job;
    }
}
