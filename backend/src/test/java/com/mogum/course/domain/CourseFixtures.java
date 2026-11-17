package com.mogum.course.domain;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

public final class CourseFixtures {
    private CourseFixtures() { }

    public static Course single(long userId) {
        return Course.single(userId, new LearningInput("자바", Level.BEGINNER, null, Purpose.GENERAL, null, null),
                "자바 기초");
    }

    public static Course single(long userId, LearningInput input, String title) {
        return Course.single(userId, input, title);
    }

    public static Course inRoadmap(Roadmap roadmap, int position, String title, String planDescription,
            List<PlannedConcept> planConcepts) {
        return Course.inRoadmap(roadmap, position, title, planDescription, planConcepts);
    }

    public static Course withStatus(Course course, CourseStatus status) {
        if (status == CourseStatus.REVIEWING && course.getStatus() == CourseStatus.NOT_PLANNED) {
            course.planLessons();
        } else {
            ReflectionTestUtils.setField(course, "status", status);
        }
        if (status == CourseStatus.LEARNING || status == CourseStatus.COMPLETED) {
            ReflectionTestUtils.setField(course, "startedAt", Instant.parse("2026-11-01T00:00:00Z"));
        }
        if (status == CourseStatus.COMPLETED) {
            ReflectionTestUtils.setField(course, "completedAt", Instant.parse("2026-11-02T00:00:00Z"));
        }
        return course;
    }
}

