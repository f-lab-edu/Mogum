package com.mogum.course.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.*;
import com.mogum.support.RepositoryTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CourseRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private RoadmapRepository roadmaps;
    @Autowired private CourseRepository courses;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "수준 {0}, 목적 {2}, 메모 {4}")
    @CsvSource({"BEGINNER,,WORK,,자바 예제", "BASICS,,INTERVIEW,,", "FAMILIAR,,GENERAL,,",
            "OTHER,실습 경험,OTHER,개인 프로젝트,"})
    @DisplayName("단일 코스는 입력과 검토 중 상태를 저장하고 계획과 학습 시각은 비운다")
    void reloadsSingleCourseWithInput(Level level, String levelOther, Purpose purpose,
                                     String purposeOther, String memo) {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var input = new LearningInput("자바", level, levelOther, purpose, purposeOther, memo);
        var saved = courses.save(CourseFixtures.single(user.getId(), input, "자바 기초"));

        // when
        var actual = courses.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getUserId()).isEqualTo(user.getId());
        assertThat(actual.getTitle()).isEqualTo("자바 기초");
        assertThat(actual.getInput()).usingRecursiveComparison().isEqualTo(input);
        assertThat(actual.getStatus()).isEqualTo(CourseStatus.REVIEWING);
        assertThat(actual.getRoadmap()).isNull();
        assertThat(actual.getPosition()).isNull();
        assertThat(actual.getPlanDescription()).isNull();
        assertThat(actual.getPlanConcepts()).isNull();
        assertThat(actual.getStartedAt()).isNull();
        assertThat(actual.getLastLessonOpenedAt()).isNull();
        assertThat(actual.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("로드맵의 코스는 입력 없이 계획의 개념을 한국어와 순서 및 계약의 JSON 키 그대로 저장한다")
    void reloadsRoadmapCourseWithOrderedPlanConcepts() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var roadmap = roadmaps.save(RoadmapFixtures.roadmap(user.getId()));
        var concepts = List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("타입", "값의 종류"));
        var saved = courses.save(CourseFixtures.inRoadmap(roadmap, 2, "자바 문법", "기본 문법을 익혀요", concepts));

        // when
        var actual = courses.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getUserId()).isEqualTo(roadmap.getUserId());
        assertThat(actual.getRoadmap().getId()).isEqualTo(roadmap.getId());
        assertThat(Hibernate.isInitialized(actual.getRoadmap())).isFalse();
        assertThat(actual.getPosition()).isEqualTo(2);
        assertThat(actual.getTitle()).isEqualTo("자바 문법");
        assertThat(actual.getPlanDescription()).isEqualTo("기본 문법을 익혀요");
        assertThat(actual.getPlanConcepts()).containsExactlyElementsOf(concepts);
        assertThat(actual.getInput()).isNull();
        assertThat(actual.getStatus()).isEqualTo(CourseStatus.NOT_PLANNED);
        assertThat(actual.getStartedAt()).isNull();
        assertThat(actual.getLastLessonOpenedAt()).isNull();
        assertThat(actual.getCompletedAt()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT plan_concepts = '[{"name":"변수","definition":"값을 담는 이름"},
                  {"name":"타입","definition":"값의 종류"}]'::jsonb FROM courses WHERE id = ?
                """, Boolean.class, actual.getId())).isTrue();
    }
}

