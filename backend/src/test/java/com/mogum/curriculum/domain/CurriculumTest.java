package com.mogum.curriculum.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurriculumTest {
    @Test
    void 독립_커리큘럼은_단계_없는_초안으로_시작한다() {
        var curriculum = Curriculum.single(29L, "독립 주제", "독립 목적", "독립 제목");

        assertEquals(29L, curriculum.getUserId());
        assertEquals("독립 주제", curriculum.getTopic());
        assertEquals("독립 목적", curriculum.getPurpose());
        assertEquals("독립 제목", curriculum.getTitle());
        assertEquals(CurriculumStatus.DRAFT, curriculum.getStatus());
        assertNull(curriculum.getStep());
        assertNull(curriculum.getId());
        assertNull(curriculum.getCreatedAt());
        assertNull(curriculum.getUpdatedAt());
    }

    @Test
    void 단계_커리큘럼은_단계의_입력과_제목_묶음의_주인을_복사한다() {
        var group = CurriculumGroup.plan(17L, "전체 주제", "전체 목적", "묶음 제목");
        var step = CurriculumStep.of(group, 3, "단계 제목", "단계 범위", "단계 주제", "단계 목적");

        var curriculum = Curriculum.forStep(step);

        assertSame(step, curriculum.getStep());
        assertEquals(17L, curriculum.getUserId());
        assertEquals("단계 제목", curriculum.getTitle());
        assertEquals("단계 주제", curriculum.getTopic());
        assertEquals("단계 목적", curriculum.getPurpose());
        assertEquals(CurriculumStatus.DRAFT, curriculum.getStatus());
        assertNull(curriculum.getId());
        assertNull(curriculum.getCreatedAt());
        assertNull(curriculum.getUpdatedAt());
    }
}
