package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.HomeTodoResponse;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class HomeCourseReadServiceIntegrationTest {
	@Autowired
	HomeCourseReadService homeCourseReadService;
	@Autowired
	CourseReadService courseReadService;
	@Autowired
	RoadmapReadService roadmapReadService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;

	@Test
	@DisplayName("이어서 배우기는 코스 읽기와 같은 다음 강의를 반환하고 DB의 상태와 시각을 바꾸지 않는다")
	void sharesNextLessonWithoutMutation() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var course = CourseFixtures.withStatus(CourseFixtures.single(userId), CourseStatus.LEARNING);
		course.openLesson(testClock.instant());
		courseRepository.save(course);
		lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1), testClock.instant()));
		var next = lessonRepository.save(LessonFixtures.lesson(course, 2));
		var expected = courseReadService.read(userId, course.getId()).nextLessonId();
		var foreignId = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(foreignId), CourseStatus.LEARNING));
		var client = testRequests.asUser(userId);
		var before = snapshot();
		testClock.advance(Duration.ofSeconds(10));
		// when
		var response = client.get().uri("/api/home/continue-learning").exchange();
		// then
		response.expectStatus().isOk().expectBody()
			.jsonPath("$.data.course.id").isEqualTo(course.getId())
			.jsonPath("$.data.course.nextLesson.id").isEqualTo(expected)
			.jsonPath("$.data.course.nextLesson.title").isEqualTo(next.getTitle())
			.jsonPath("$.data.course.progress").doesNotExist()
			.jsonPath("$.data.course.input").doesNotExist()
			.jsonPath("$.data.course.userId").doesNotExist();
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@DisplayName("할 일은 코스와 로드맵을 생성 시각 내림차순과 ID 내림차순 및 종류 이름 오름차순으로 섞는다")
	void ordersMixedTodosWithoutMutation() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var old = courseRepository.save(CourseFixtures.single(userId));
		testClock.advance(Duration.ofSeconds(1));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		testClock.advance(Duration.ofSeconds(1));
		var sameTime = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "로드맵 코스", "설명", List.of()), CourseStatus.REVIEWING));
		var tiedCourse = courseRepository.save(CourseFixtures.single(userId));
		testClock.advance(Duration.ofSeconds(1));
		var ready = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		courseRepository.save(CourseFixtures.inRoadmap(ready, 1, "첫", "설명", List.of()));
		long foreignId = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		courseRepository.save(CourseFixtures.single(foreignId));
		roadmapRepository.save(RoadmapFixtures.roadmap(foreignId));
		var before = snapshot();
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = homeCourseReadService.todos(userId);
		// then
		assertThat(result.items()).containsExactly(
			new HomeTodoResponse.Item(HomeTodoResponse.Type.ROADMAP_NEXT_COURSE, ready.getId(), ready.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, tiedCourse.getId(), tiedCourse.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, sameTime.getId(), sameTime.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.ROADMAP_REVIEW, roadmap.getId(), roadmap.getTitle()),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, old.getId(), old.getTitle()));
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "작업 상태={0}")
	@ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
	@DisplayName("실제 강의 목록 작업이 성공하지 않았으면 로드맵의 다음 코스와 홈의 할 일에서 함께 빠진다")
	void sharesPlanningJobExclusion(String status) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		var course = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));
		var job = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, course.getId()));
		if (status.equals("RUNNING")) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
				testClock.instant().plusSeconds(960));
		} else if (status.equals("FAILED")) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		} else if (status.equals("SUCCEEDED")) {
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), course.getId(), null, null, testClock.instant());
		}
		var expected = roadmapReadService.read(userId, roadmap.getId()).nextCourse();
		var before = snapshot();
		// when
		var result = homeCourseReadService.todos(userId);
		// then
		assertThat(result.items()).hasSize(status.equals("SUCCEEDED") ? 1 : 0);
		assertThat(expected != null && expected.enabled()).isEqualTo(!result.items().isEmpty());
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@DisplayName("검토 중 코스에 다시 짜기 작업이 있어도 할 일에서 숨기지 않는다")
	void retainsReviewingCourseDuringReplan() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var course = courseRepository.save(CourseFixtures.single(userId));
		generationJobRepository.save(GenerationJobFixtures.replanCourse(userId, course.getId(), null));
		var client = testRequests.asUser(userId);
		var before = snapshot();
		// when
		var response = client.get().uri("/api/home/todos").exchange();
		// then
		response.expectStatus().isOk().expectBody()
			.jsonPath("$.data.items.length()").isEqualTo(1)
			.jsonPath("$.data.items[0].type").isEqualTo("COURSE_REVIEW")
			.jsonPath("$.data.items[0].targetId").isEqualTo(course.getId())
			.jsonPath("$.data.items[0].title").isEqualTo(course.getTitle())
			.jsonPath("$.data.items[0].job").doesNotExist();
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "경로={0}")
	@ValueSource(strings = {"/api/home/continue-learning", "/api/home/todos"})
	@DisplayName("홈의 두 코스 읽기는 로그인하지 않으면 거절한다")
	void rejectsAnonymousReads(String path) {
		// when
		var result = testRequests.anonymous().get().uri(path).exchange();
		// then
		result.expectStatus().isUnauthorized();
	}

	@ParameterizedTest(name = "경로={0}, 상태={1}")
	@CsvSource({"/api/home/continue-learning,APPLIED", "/api/home/continue-learning,REJECTED",
		"/api/home/todos,APPLIED", "/api/home/todos,REJECTED"})
	@DisplayName("승인되지 않은 계정은 홈의 두 코스 읽기를 쓸 수 없다")
	void rejectsUnapprovedReads(String path, UserStatus status) {
		// given
		long userId = userRepository.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"), status)).getId();
		var client = testRequests.asUser(userId);
		// when
		var result = client.get().uri(path).exchange();
		// then
		result.expectStatus().isForbidden().expectBody()
			.jsonPath("$.code").isEqualTo("ACCOUNT_NOT_APPROVED");
	}

	private List<String> snapshot() {
		return List.of("users", "courses", "roadmaps", "lessons", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForObject(
				"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id), '[]'::jsonb)::text FROM " + table + " t",
				String.class))
			.toList();
	}
}
