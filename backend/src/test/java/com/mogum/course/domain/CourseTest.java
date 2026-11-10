package com.mogum.course.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseTest {
    @Test
    @DisplayName("단일 코스는 입력과 제목을 담고 계획과 학습 시각 없이 검토 중으로 시작한다")
    void createsSingleCourseWithInput() {
        // given
        var input = new LearningInput("자바", Level.BASICS, null, Purpose.WORK, null, null);

        // when
        var course = Course.single(17L, input, "자바 기초");

        // then
        assertThat(course.getId()).isNull();
        assertThat(course.getCreatedAt()).isNull();
        assertThat(course.getUpdatedAt()).isNull();
        assertThat(course.getUserId()).isEqualTo(17L);
        assertThat(course.getInput()).isSameAs(input);
        assertThat(course.getTitle()).isEqualTo("자바 기초");
        assertThat(course.getStatus()).isEqualTo(CourseStatus.REVIEWING);
        assertThat(course.getRoadmap()).isNull();
        assertThat(course.getPosition()).isNull();
        assertThat(course.getPlanDescription()).isNull();
        assertThat(course.getPlanConcepts()).isNull();
        assertThat(course.getStartedAt()).isNull();
        assertThat(course.getLastLessonOpenedAt()).isNull();
        assertThat(course.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("로드맵의 코스는 로드맵의 주인과 계획을 담고 입력 없이 짜기 전으로 시작한다")
    void createsRoadmapCourseWithOwnerAndPlan() {
        // given
        var roadmap = RoadmapFixtures.roadmap(91L);
        var concepts = List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("타입", "값의 종류"));

        // when
        var course = Course.inRoadmap(roadmap, 2, "자바 문법", "기본 문법을 익혀요", concepts);

        // then
        assertThat(course.getId()).isNull();
        assertThat(course.getCreatedAt()).isNull();
        assertThat(course.getUpdatedAt()).isNull();
        assertThat(course.getUserId()).isEqualTo(91L);
        assertThat(course.getRoadmap()).isSameAs(roadmap);
        assertThat(course.getPosition()).isEqualTo(2);
        assertThat(course.getTitle()).isEqualTo("자바 문법");
        assertThat(course.getPlanDescription()).isEqualTo("기본 문법을 익혀요");
        assertThat(course.getPlanConcepts()).containsExactlyElementsOf(concepts);
        assertThat(course.getStatus()).isEqualTo(CourseStatus.NOT_PLANNED);
        assertThat(course.getInput()).isNull();
        assertThat(course.getStartedAt()).isNull();
        assertThat(course.getLastLessonOpenedAt()).isNull();
        assertThat(course.getCompletedAt()).isNull();
    }
}

