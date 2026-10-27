package com.mogum.curriculum.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurriculumGroupTest {
    @Test
    void 전체_목표와_주인으로_분할안을_만든다() {
        var group = CurriculumGroup.plan(17L, "전체 주제", "전체 목적", "묶음 제목");

        assertEquals(17L, group.getUserId());
        assertEquals("전체 주제", group.getTopic());
        assertEquals("전체 목적", group.getPurpose());
        assertEquals("묶음 제목", group.getTitle());
        assertNull(group.getId());
        assertNull(group.getCreatedAt());
        assertNull(group.getUpdatedAt());
    }
}
