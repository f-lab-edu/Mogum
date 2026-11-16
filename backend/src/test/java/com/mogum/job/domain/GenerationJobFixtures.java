package com.mogum.job.domain;

import com.mogum.course.domain.LearningInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;

public final class GenerationJobFixtures {
    private GenerationJobFixtures() { }

    public static GenerationJob newCourse(long userId, LearningInput input) {
        return GenerationJob.newCourse(userId, input);
    }

    public static GenerationJob newRoadmap(long userId, LearningInput input) {
        return GenerationJob.newRoadmap(userId, input);
    }

    public static GenerationJob planRoadmapCourse(long userId, long courseId) {
        return GenerationJob.planRoadmapCourse(userId, courseId);
    }

    public static GenerationJob replanCourse(long userId, long courseId, String feedback) {
        return GenerationJob.replanCourse(userId, courseId, feedback);
    }

    public static GenerationJob replanRoadmap(long userId, long roadmapId, String feedback) {
        return GenerationJob.replanRoadmap(userId, roadmapId, feedback);
    }

    public static GenerationJob material(long userId, long lessonId) {
        return GenerationJob.material(userId, lessonId);
    }

    public static void failed(GenerationJob job, JobErrorType error) {
        withStatus(job, JobStatus.FAILED);
        ReflectionTestUtils.setField(job, "errorType", error);
        ReflectionTestUtils.setField(job, "finishedAt", Instant.parse("2026-11-15T00:00:00Z"));
    }

    public static void running(GenerationJob job) { withStatus(job, JobStatus.RUNNING); }

    public static void withStatus(GenerationJob job, JobStatus status) {
        ReflectionTestUtils.setField(job, "status", status);
    }

    public static void running(JdbcTemplate jdbc, long id, int claimCount, Instant startedAt, Instant leaseUntil) {
        jdbc.update("""
                UPDATE generation_jobs SET status = 'RUNNING', claim_count = ?, started_at = ?, lease_until = ?,
                error_type = NULL, finished_at = NULL, updated_at = ? WHERE id = ?
                """, claimCount, Timestamp.from(startedAt), Timestamp.from(leaseUntil), Timestamp.from(startedAt), id);
    }

    public static void failed(JdbcTemplate jdbc, long id, JobErrorType errorType, Instant finishedAt) {
        jdbc.update("""
                UPDATE generation_jobs SET status = 'FAILED', error_type = ?, lease_until = NULL,
                finished_at = ?, updated_at = ? WHERE id = ?
                """, errorType.name(), Timestamp.from(finishedAt), Timestamp.from(finishedAt), id);
    }

    public static void succeeded(JdbcTemplate jdbc, long id, Long courseId, Long roadmapId, Long lessonId,
                                 Instant finishedAt) {
        jdbc.update("""
                UPDATE generation_jobs SET status = 'SUCCEEDED', course_id = ?, roadmap_id = ?, lesson_id = ?,
                lease_until = NULL, error_type = NULL, finished_at = ?, updated_at = ? WHERE id = ?
                """, courseId, roadmapId, lessonId, Timestamp.from(finishedAt), Timestamp.from(finishedAt), id);
    }
}
