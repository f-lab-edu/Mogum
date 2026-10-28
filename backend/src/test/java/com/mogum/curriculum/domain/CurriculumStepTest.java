package com.mogum.curriculum.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurriculumStepTest {
    @Test
    void 묶음의_순서와_생성_입력을_보존한다() {
        var group = CurriculumGroup.plan(17L, "전체 주제", "전체 목적", "묶음 제목");
        var step = CurriculumStep.of(group, 3, "단계 제목", "단계 범위", "단계 주제", "단계 목적");

        assertSame(group, step.getGroup());
        assertEquals(3, step.getOrderNo());
        assertEquals("단계 제목", step.getTitle());
        assertEquals("단계 범위", step.getScope());
        assertEquals("단계 주제", step.getTopic());
        assertEquals("단계 목적", step.getPurpose());
        assertNull(step.getId());
        assertNull(step.getCreatedAt());
        assertNull(step.getUpdatedAt());
    }
}
