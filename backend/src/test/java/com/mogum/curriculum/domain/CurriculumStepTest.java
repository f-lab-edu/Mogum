package com.mogum.curriculum.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurriculumStepTest {
    @Test
    @DisplayName("단계는 묶음의 순서와 생성 입력을 보존한다")
    void preservesGroupOrderAndGenerationInput() {
        // given
        var group = CurriculumFixtures.group(17L, "전체 주제", "전체 목적", "묶음 제목");

        // when
        var step = CurriculumStep.of(group, 3, "단계 제목", "단계 범위", "단계 주제", "단계 목적");

        // then
        assertThat(step.getGroup()).isSameAs(group);
        assertThat(step.getOrderNo()).isEqualTo(3);
        assertThat(step.getTitle()).isEqualTo("단계 제목");
        assertThat(step.getScope()).isEqualTo("단계 범위");
        assertThat(step.getTopic()).isEqualTo("단계 주제");
        assertThat(step.getPurpose()).isEqualTo("단계 목적");
        assertThat(step.getId()).isNull();
        assertThat(step.getCreatedAt()).isNull();
        assertThat(step.getUpdatedAt()).isNull();
    }
}
