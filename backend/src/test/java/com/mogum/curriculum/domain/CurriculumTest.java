package com.mogum.curriculum.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurriculumTest {
    @Test
    @DisplayName("독립 커리큘럼은 입력을 보존하고 단계 없는 초안으로 시작한다")
    void createsSingleCurriculumAsDraftWithoutStep() {
        // when
        var curriculum = Curriculum.single(29L, "독립 주제", "독립 목적", "독립 제목");

        // then
        assertThat(curriculum.getUserId()).isEqualTo(29L);
        assertThat(curriculum.getTopic()).isEqualTo("독립 주제");
        assertThat(curriculum.getPurpose()).isEqualTo("독립 목적");
        assertThat(curriculum.getTitle()).isEqualTo("독립 제목");
        assertThat(curriculum.getStatus()).isEqualTo(CurriculumStatus.DRAFT);
        assertThat(curriculum.getStep()).isNull();
        assertThat(curriculum.getId()).isNull();
        assertThat(curriculum.getCreatedAt()).isNull();
        assertThat(curriculum.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("단계 커리큘럼은 단계의 입력과 제목 및 묶음의 주인을 복사한다")
    void copiesStepInputAndGroupOwnerIntoDraft() {
        // given
        var group = CurriculumFixtures.group(17L, "전체 주제", "전체 목적", "묶음 제목");
        var step = CurriculumFixtures.step(group, 3, "단계 제목", "단계 범위", "단계 주제", "단계 목적");

        // when
        var curriculum = Curriculum.forStep(step);

        // then
        assertThat(curriculum.getStep()).isSameAs(step);
        assertThat(curriculum.getUserId()).isEqualTo(17L);
        assertThat(curriculum.getTitle()).isEqualTo("단계 제목");
        assertThat(curriculum.getTopic()).isEqualTo("단계 주제");
        assertThat(curriculum.getPurpose()).isEqualTo("단계 목적");
        assertThat(curriculum.getStatus()).isEqualTo(CurriculumStatus.DRAFT);
        assertThat(curriculum.getId()).isNull();
        assertThat(curriculum.getCreatedAt()).isNull();
        assertThat(curriculum.getUpdatedAt()).isNull();
    }
}
