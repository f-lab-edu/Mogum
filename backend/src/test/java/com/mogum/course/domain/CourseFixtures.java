package com.mogum.course.domain;

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
}

