package com.mogum.course.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapTest {
    @Test
    @DisplayName("로드맵 계획은 입력과 제목과 설명을 담고 검토 중으로 시작한다")
    void plansRoadmapWithInputAndGeneratedContent() {
        // given
        var input = new LearningInput("서버 개발", Level.OTHER, "실습 경험", Purpose.OTHER, "개인 프로젝트", "자바 예제");

        // when
        var roadmap = Roadmap.plan(42L, input, "서버 개발 로드맵", "서버를 만들 수 있어요");

        // then
        assertThat(roadmap.getId()).isNull();
        assertThat(roadmap.getCreatedAt()).isNull();
        assertThat(roadmap.getUpdatedAt()).isNull();
        assertThat(roadmap.getUserId()).isEqualTo(42L);
        assertThat(roadmap.getInput()).isSameAs(input);
        assertThat(input.getTopic()).isEqualTo("서버 개발");
        assertThat(input.getLevel()).isEqualTo(Level.OTHER);
        assertThat(input.getLevelOther()).isEqualTo("실습 경험");
        assertThat(input.getPurpose()).isEqualTo(Purpose.OTHER);
        assertThat(input.getPurposeOther()).isEqualTo("개인 프로젝트");
        assertThat(input.getMemo()).isEqualTo("자바 예제");
        assertThat(roadmap.getTitle()).isEqualTo("서버 개발 로드맵");
        assertThat(roadmap.getDescription()).isEqualTo("서버를 만들 수 있어요");
        assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
    }
}

