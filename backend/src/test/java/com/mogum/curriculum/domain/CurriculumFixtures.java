package com.mogum.curriculum.domain;

import com.mogum.common.persistence.AuditedEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

public final class CurriculumFixtures {
    private CurriculumFixtures() { }

    public static CurriculumGroup group(long userId) {
        return group(userId, "전체 주제", "전체 목적", "묶음 제목");
    }

    public static CurriculumGroup group(long userId, String topic, String purpose, String title) {
        return CurriculumGroup.plan(userId, topic, purpose, title);
    }

    public static CurriculumStep step(CurriculumGroup group) {
        return step(group, 1, "단계 제목", "단계 범위", "단계 주제", "단계 목적");
    }

    public static CurriculumStep step(CurriculumGroup group, int orderNo, String title,
                                      String scope, String topic, String purpose) {
        return CurriculumStep.of(group, orderNo, title, scope, topic, purpose);
    }

    public static Curriculum curriculum(long userId) {
        return curriculum(userId, "학습 주제", "학습 목적", "커리큘럼");
    }

    public static Curriculum curriculum(long userId, String topic, String purpose, String title) {
        return Curriculum.single(userId, topic, purpose, title);
    }

    public static Curriculum curriculumForStep(CurriculumStep step) {
        return Curriculum.forStep(step);
    }

    public static Lesson lesson(Curriculum curriculum) {
        return lesson(curriculum, 1, "첫 강의", "학습 목표", List.of("개념"));
    }

    public static Lesson lesson(Curriculum curriculum, int orderNo, String title,
                                String objective, List<String> keywords) {
        return Lesson.of(curriculum, orderNo, title, objective, keywords);
    }

    public static <T extends AuditedEntity> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
