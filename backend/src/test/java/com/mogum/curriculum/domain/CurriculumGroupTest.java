package com.mogum.curriculum.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurriculumGroupTest {
    @Test
    @DisplayName("전체 목표와 주인을 보존한 분할안을 만든다")
    void preservesOwnerAndGoalInPlan() {
        // when
        var group = CurriculumGroup.plan(17L, "전체 주제", "전체 목적", "묶음 제목");

        // then
        assertThat(group.getUserId()).isEqualTo(17L);
        assertThat(group.getTopic()).isEqualTo("전체 주제");
        assertThat(group.getPurpose()).isEqualTo("전체 목적");
        assertThat(group.getTitle()).isEqualTo("묶음 제목");
        assertThat(group.getId()).isNull();
        assertThat(group.getCreatedAt()).isNull();
        assertThat(group.getUpdatedAt()).isNull();
    }
}
