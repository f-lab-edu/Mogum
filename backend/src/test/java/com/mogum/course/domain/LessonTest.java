package com.mogum.course.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LessonTest {
    @Test
    @DisplayName("강의 목록의 한 줄은 코스와 순서와 글을 담고 학습 완료 시각은 비운다")
    void listsLessonWithCourseAndConcepts() {
        // given
        var course = CourseFixtures.single(21L);
        var concepts = List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("타입", "값의 종류"));

        // when
        var lesson = Lesson.listed(course, 3, "변수와 타입", "값을 다루는 법을 배워요", concepts);

        // then
        assertThat(lesson.getId()).isNull();
        assertThat(lesson.getCreatedAt()).isNull();
        assertThat(lesson.getUpdatedAt()).isNull();
        assertThat(lesson.getCourse()).isSameAs(course);
        assertThat(lesson.getPosition()).isEqualTo(3);
        assertThat(lesson.getTitle()).isEqualTo("변수와 타입");
        assertThat(lesson.getDescription()).isEqualTo("값을 다루는 법을 배워요");
        assertThat(lesson.getConcepts()).containsExactlyElementsOf(concepts);
        assertThat(lesson.getCompletedAt()).isNull();
    }
}

