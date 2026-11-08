package com.mogum.curriculum.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LessonTest {
    @Test
    @DisplayName("강의는 권장 순서와 내용을 보존하고 미시작으로 시작한다")
    void preservesOrderAndContentInNotStartedLesson() {
        // given
        var curriculum = CurriculumFixtures.curriculum(29L, "주제", null, "커리큘럼 제목");
        var keywords = List.of("첫 키워드", "둘째 키워드");

        // when
        var lesson = Lesson.of(curriculum, 4, "강의 제목", "강의 목표", keywords);

        // then
        assertThat(lesson.getCurriculum()).isSameAs(curriculum);
        assertThat(lesson.getOrderNo()).isEqualTo(4);
        assertThat(lesson.getTitle()).isEqualTo("강의 제목");
        assertThat(lesson.getObjective()).isEqualTo("강의 목표");
        assertThat(lesson.getKeywords()).isEqualTo(keywords);
        assertThat(lesson.getStatus()).isEqualTo(LessonStatus.NOT_STARTED);
        assertThat(lesson.getId()).isNull();
        assertThat(lesson.getCreatedAt()).isNull();
        assertThat(lesson.getUpdatedAt()).isNull();
    }
}
