package com.mogum.curriculum.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LessonTest {
    @Test
    void 강의는_권장_순서와_내용을_보존하고_미시작으로_시작한다() {
        var curriculum = Curriculum.single(29L, "주제", null, "커리큘럼 제목");
        var keywords = List.of("첫 키워드", "둘째 키워드");

        var lesson = Lesson.of(curriculum, 4, "강의 제목", "강의 목표", keywords);

        assertSame(curriculum, lesson.getCurriculum());
        assertEquals(4, lesson.getOrderNo());
        assertEquals("강의 제목", lesson.getTitle());
        assertEquals("강의 목표", lesson.getObjective());
        assertEquals(keywords, lesson.getKeywords());
        assertEquals(LessonStatus.NOT_STARTED, lesson.getStatus());
        assertNull(lesson.getId());
        assertNull(lesson.getCreatedAt());
        assertNull(lesson.getUpdatedAt());
    }
}
