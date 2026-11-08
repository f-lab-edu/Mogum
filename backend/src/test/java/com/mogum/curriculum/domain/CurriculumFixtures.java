package com.mogum.curriculum.domain;

import java.util.List;

public final class CurriculumFixtures {
    private CurriculumFixtures() { }

    public static Curriculum curriculum(long userId) {
        return Curriculum.single(userId, "학습 주제", "학습 목적", "커리큘럼");
    }

    public static Lesson lesson(Curriculum curriculum) {
        return Lesson.of(curriculum, 1, "첫 강의", "학습 목표", List.of("개념"));
    }
}
