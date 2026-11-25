package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.HomeTodoResponse;
import com.mogum.course.repository.CourseJobRepository.PlanningJob;
import com.mogum.course.repository.FakeCourseJobRepository;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeLessonRepository;
import com.mogum.course.repository.FakeRoadmapRepository;

class HomeCourseReadServiceTest {
	private final FakeCourseRepository courseRepository = new FakeCourseRepository();
	private final FakeLessonRepository lessonRepository = new FakeLessonRepository();
	private final FakeRoadmapRepository roadmapRepository = new FakeRoadmapRepository();
	private final FakeCourseJobRepository courseJobRepository = new FakeCourseJobRepository();
	private final HomeCourseReadService homeCourseReadService = new HomeCourseReadService(
		courseRepository, lessonRepository, roadmapRepository,
		new RoadmapReadService(roadmapRepository, courseRepository, courseJobRepository));

	@Test
	@DisplayName("학습 중 코스 가운데 강의를 더 최근에 연 코스를 고른다")
	void choosesMostRecentlyOpenedCourse() {
		// given
		var recent = learning("최근", "2026-11-01T00:00:00Z");
		recent.openLesson(Instant.parse("2026-11-03T00:00:00Z"));
		var older = learning("이전", "2026-11-02T00:00:00Z");
		older.openLesson(Instant.parse("2026-11-02T00:00:00Z"));
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course().id()).isEqualTo(recent.getId());
		assertThat(result.course().title()).isEqualTo("최근");
	}

	@Test
	@DisplayName("강의를 연 코스가 없으면 가장 최근에 시작한 코스를 고른다")
	void fallsBackToMostRecentlyStartedCourse() {
		// given
		var recent = learning("최근 시작", "2026-11-03T00:00:00Z");
		learning("이전 시작", "2026-11-01T00:00:00Z");
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course().id()).isEqualTo(recent.getId());
	}

	@Test
	@DisplayName("강의를 연 코스는 방금 시작한 코스보다 먼저 보인다")
	void prefersOpenedCourseOverNewlyStartedCourse() {
		// given
		var opened = learning("열어 본 코스", "2026-11-01T00:00:00Z");
		opened.openLesson(Instant.parse("2026-11-02T00:00:00Z"));
		learning("방금 시작", "2026-11-03T00:00:00Z");
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course().id()).isEqualTo(opened.getId());
	}

	@ParameterizedTest(name = "강의 열림={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("고르는 시각이 같으면 ID가 더 큰 코스를 고른다")
	void breaksContinueTiesByDescendingId(boolean opened) {
		// given
		var first = learning("하나", "2026-11-01T00:00:00Z");
		var second = learning("둘", "2026-11-01T00:00:00Z");
		if (opened) {
			first.openLesson(Instant.parse("2026-11-02T00:00:00Z"));
			second.openLesson(first.getLastLessonOpenedAt());
		}
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course().id()).isEqualTo(second.getId());
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(value = CourseStatus.class, names = "LEARNING", mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("학습 중이 아닌 코스와 남의 학습 중 코스는 이어서 배우기에서 빠진다")
	void omitsIneligibleAndForeignCourses(CourseStatus status) {
		// given
		courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(1L), status));
		courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(2L), CourseStatus.LEARNING));
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course()).isNull();
	}

	@Test
	@DisplayName("학습 중 코스가 하나도 없으면 이어서 배우기는 비어 있다")
	void returnsEmptyContinueLearning() {
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course()).isNull();
	}

	@Test
	@DisplayName("완료하지 않은 가장 앞 강의의 ID와 제목을 싣는다")
	void includesFirstIncompleteLesson() {
		// given
		var course = learning("자바", "2026-11-01T00:00:00Z");
		lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).getFirst()
			.complete(Instant.parse("2026-11-02T00:00:00Z"));
		var next = lessonRepository.save(LessonFixtures.lesson(course, 2, "다음 강의", "설명", List.of()));
		// when
		var result = homeCourseReadService.continueLearning(1L);
		// then
		assertThat(result.course().nextLesson().id()).isEqualTo(next.getId());
		assertThat(result.course().nextLesson().title()).isEqualTo("다음 강의");
	}

	@Test
	@DisplayName("검토 중 단일 코스와 로드맵 코스 및 로드맵 계획을 할 일에 넣는다")
	void includesReviewingResultsEvenDuringReplan() {
		// given
		var single = courseRepository.save(CourseFixtures.single(1L));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(1L));
		var nested = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "로드맵 코스", "설명", List.of()), CourseStatus.REVIEWING));
		courseJobRepository.unfinishedReplan = true;
		// when
		var result = homeCourseReadService.todos(1L);
		// then
		assertThat(result.items()).containsExactly(
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, nested.getId(), nested.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, single.getId(), single.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.ROADMAP_REVIEW, roadmap.getId(), roadmap.getTitle()));
	}

	@ParameterizedTest(name = "앞 코스={0}")
	@EnumSource(value = CourseStatus.class, names = {"REVIEWING", "LEARNING", "COMPLETED"})
	@DisplayName("진행 중 로드맵은 로드맵 읽기의 다음 코스가 활성화될 때만 할 일에 들어간다")
	void agreesWithRoadmapNextCourse(CourseStatus previousStatus) {
		// given
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "앞", "설명", List.of()), previousStatus));
		courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "다음", "설명", List.of()));
		var expected = new RoadmapReadService(roadmapRepository, courseRepository, courseJobRepository)
			.read(1L, roadmap.getId()).nextCourse().enabled();
		// when
		var result = homeCourseReadService.todos(1L);
		// then
		assertThat(result.items().stream().anyMatch(item -> item.type() == HomeTodoResponse.Type.ROADMAP_NEXT_COURSE))
			.isEqualTo(expected);
	}

	@ParameterizedTest(name = "실패={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("다음 코스의 성공하지 않은 강의 목록 작업이 있으면 할 일에서 빠진다")
	void excludesRoadmapWithPlanningJob(boolean failed) {
		// given
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
		var course = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));
		courseJobRepository.planningJobs.add(new PlanningJob(course.getId(), 1L, failed));
		// when
		var result = homeCourseReadService.todos(1L);
		// then
		assertThat(result.items()).isEmpty();
	}

	@Test
	@DisplayName("학습 중 코스와 다 마친 로드맵 및 남의 결과는 할 일에 넣지 않는다")
	void excludesFinishedLearningAndForeignResults() {
		// given
		learning("배우는 중", "2026-11-01T00:00:00Z");
		roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(1L), RoadmapStatus.COMPLETED));
		roadmapRepository.save(RoadmapFixtures.roadmap(2L));
		courseRepository.save(CourseFixtures.single(2L));
		// when
		var result = homeCourseReadService.todos(1L);
		// then
		assertThat(result.items()).isEmpty();
	}

	@Test
	@DisplayName("진행 중 로드맵의 첫 코스가 만들 수 있으면 로드맵 제목과 ID를 싣는다")
	void includesReadyFirstCourseRoadmap() {
		// given
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
		courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));
		// when
		var result = homeCourseReadService.todos(1L);
		// then
		assertThat(result.items()).containsExactly(new HomeTodoResponse.Item(
			HomeTodoResponse.Type.ROADMAP_NEXT_COURSE, roadmap.getId(), roadmap.getTitle()));
	}

	private Course learning(String title, String startedAt) {
		var course = CourseFixtures.single(1L);
		course.rename(title);
		course.start(Instant.parse(startedAt));
		courseRepository.save(course);
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		return course;
	}
}
