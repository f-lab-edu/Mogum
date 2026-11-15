package com.mogum.course.domain;

import java.util.List;

public final class LessonFixtures {
    private LessonFixtures() { }

    public static Lesson lesson(Course course, int position) {
        return Lesson.listed(course, position, "변수", "변수를 배워요", List.of(new PlannedConcept("변수", "값을 담는 이름")));
    }

    public static Lesson lesson(Course course, int position, String title, String description,
                                List<PlannedConcept> concepts) {
        return Lesson.listed(course, position, title, description, concepts);
    }
}

