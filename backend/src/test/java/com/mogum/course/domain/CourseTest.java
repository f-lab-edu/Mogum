package com.mogum.course.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CourseTest {
	@Test
	@DisplayName("검토 중 코스를 시작하면 학습 중 상태와 시작 시각을 함께 적는다")
	void startsReviewingCourse() {
		// given
		var course = CourseFixtures.single(1L);
		var now = Instant.parse("2026-11-18T00:00:00Z");

		// when
		course.start(now);

		// then
		assertThat(course.getStatus()).isEqualTo(CourseStatus.LEARNING);
		assertThat(course.getStartedAt()).isEqualTo(now);
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(value = CourseStatus.class, names = {"NOT_PLANNED", "LEARNING", "COMPLETED"})
	@DisplayName("검토 중이 아니면 도메인은 시작을 거절하고 시각과 상태를 보존한다")
	void rejectsStartOutsideReviewing(CourseStatus status) {
		// given
		var course = CourseFixtures.withStatus(CourseFixtures.inRoadmap(
			RoadmapFixtures.roadmap(1L), 1, "코스", "계획", List.of()), status);
		var startedAt = course.getStartedAt();

		// when & then
		assertThatThrownBy(() -> course.start(Instant.parse("2026-11-18T00:00:00Z")))
			.isInstanceOf(IllegalStateException.class);
		assertThat(course.getStatus()).isEqualTo(status);
		assertThat(course.getStartedAt()).isEqualTo(startedAt);
	}

	@Test
	@DisplayName("제목을 고쳐도 입력과 상태는 유지한다")
	void renamesCourse() {
		// given
		var course = CourseFixtures.single(1L);
		var input = course.getInput();

		// when
		course.rename("새 제목");

		// then
		assertThat(course.getTitle()).isEqualTo("새 제목");
		assertThat(course.getStatus()).isEqualTo(CourseStatus.REVIEWING);
		assertThat(course.getInput()).isSameAs(input);
	}

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

	@Test
	@DisplayName("강의 목록이 생기면 짜기 전 코스만 검토 중으로 바뀌고 계획은 그대로다")
	void reviewsPlannedCourse() {
		// given
		var course = CourseFixtures.inRoadmap(RoadmapFixtures.roadmap(1L), 1, "계획 제목", "계획 설명",
			List.of(new PlannedConcept("개념", "정의")));

		// when
		course.planLessons();

		// then
		assertThat(course.getStatus()).isEqualTo(CourseStatus.REVIEWING);
		assertThat(course.getTitle()).isEqualTo("계획 제목");
		assertThat(course.getPlanDescription()).isEqualTo("계획 설명");
		assertThat(course.getPlanConcepts()).containsExactly(new PlannedConcept("개념", "정의"));
		assertThatThrownBy(course::planLessons).isInstanceOf(IllegalStateException.class);
	}
}
