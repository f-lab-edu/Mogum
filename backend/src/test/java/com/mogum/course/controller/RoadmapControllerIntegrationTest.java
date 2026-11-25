package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class RoadmapControllerIntegrationTest {
	@Autowired
	UserRepository userRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;

	@ParameterizedTest(name = "강의 목록 작업={0}")
	@ValueSource(strings = {"NONE", "QUEUED", "RUNNING", "FAILED", "SUCCEEDED", "DISCARDED"})
	@DisplayName("로드맵은 작업으로 진행과 기다릴 작업을 읽고 작업 없는 첫 짜기 전 코스를 고른다")
	void readsPlanningProgressWithoutMutation(String status) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "둘", "다음 계획", List.of()));
		var first = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "첫 계획",
			List.of(new PlannedConcept("개념", "정의"))));
		Long jobId = null;
		if (!status.equals("NONE")) {
			var job = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, first.getId()));
			jobId = job.getId();
			if (status.equals("RUNNING")) {
				GenerationJobFixtures.running(jdbcTemplate, jobId, 1,
					testClock.instant(), testClock.instant().plusSeconds(360));
			} else if (status.equals("FAILED")) {
				GenerationJobFixtures.failed(jdbcTemplate, jobId, JobErrorType.INVALID_OUTPUT, testClock.instant());
			} else if (status.equals("SUCCEEDED")) {
				first.planLessons(null);
				courseRepository.save(first);
				GenerationJobFixtures.succeeded(jdbcTemplate, jobId, first.getId(), null, null, testClock.instant());
			} else if (status.equals("DISCARDED")) {
				jdbcTemplate.update("DELETE FROM generation_jobs WHERE id = ?", jobId);
			}
		}
		var client = testRequests.asUser(userId);
		var before = state();
		boolean noJob = status.equals("NONE") || status.equals("DISCARDED");
		var progress = switch (status) {
			case "QUEUED", "RUNNING" -> "GENERATING";
			case "FAILED" -> "FAILED";
			case "SUCCEEDED" -> "GENERATED";
			default -> "NOT_PLANNED";
		};
		var jobField = noJob || status.equals("SUCCEEDED") ? "" : ",\"jobId\":" + jobId;

		// when
		var result = client.get().uri("/api/roadmaps/" + roadmap.getId()).exchange();

		// then
		result.expectStatus().isOk().expectBody().json("""
			{"data":{"id":%d,"status":"IN_PROGRESS","title":"자바 로드맵",
			"description":"자바로 프로그램을 만들 수 있어요","courses":[
			{"id":%d,"position":1,"title":"첫","description":"첫 계획",
			"concepts":[{"name":"개념","definition":"정의"}],"status":"%s","progress":"%s"%s},
			{"id":%d,"position":2,"title":"둘","description":"다음 계획","concepts":[],
			"status":"NOT_PLANNED","progress":"NOT_PLANNED"}],"nextCourse":{"courseId":%d,"enabled":%s}}}
			""".formatted(roadmap.getId(), first.getId(), first.getStatus(), progress, jobField,
			second.getId(), noJob ? first.getId() : second.getId(), noJob), JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "앞 코스={0}")
	@EnumSource(value = CourseStatus.class, names = {"REVIEWING", "LEARNING", "COMPLETED"})
	@DisplayName("만들어진 코스는 작업 없이 만들어짐으로 보이고 바로 앞 코스를 다 마치면 다음이 열린다")
	void enablesNextOnlyAfterPreviousCourseCompleted(CourseStatus status) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()), status));
		var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "둘", "설명", List.of()));
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.get().uri("/api/roadmaps/" + roadmap.getId()).exchange();

		// then
		result.expectStatus().isOk().expectBody()
			.jsonPath("$.data.courses[0].progress").isEqualTo("GENERATED")
			.jsonPath("$.data.courses[0].jobId").doesNotExist()
			.jsonPath("$.data.nextCourse.courseId").isEqualTo(second.getId())
			.jsonPath("$.data.nextCourse.enabled").isEqualTo(status == CourseStatus.COMPLETED);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "로드맵={0}")
	@EnumSource(RoadmapStatus.class)
	@DisplayName("검토 중과 다 마친 로드맵 및 마지막까지 만든 진행 중 로드맵은 다음 코스를 싣지 않는다")
	void omitsNextCourseWhenUnavailable(RoadmapStatus status) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId), status));
		var courseStatus = switch (status) {
			case REVIEWING -> CourseStatus.NOT_PLANNED;
			case IN_PROGRESS -> CourseStatus.LEARNING;
			case COMPLETED -> CourseStatus.COMPLETED;
		};
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()), courseStatus));
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.get().uri("/api/roadmaps/" + roadmap.getId()).exchange();

		// then
		result.expectStatus().isOk().expectBody().jsonPath("$.data.nextCourse").doesNotExist();
		assertThat(state()).isEqualTo(before);
	}

	@Test
	@DisplayName("로드맵 목록은 본인의 모든 상태를 최근 생성 순으로 읽고 완료한 코스 수를 계산한다")
	void listsOwnedRoadmapsNewestFirstWithProgress() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var first = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		courseRepository.save(CourseFixtures.inRoadmap(first, 1, "첫", "계획", List.of()));
		var second = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(second, 1, "첫", "계획", List.of()), CourseStatus.COMPLETED));
		courseRepository.save(CourseFixtures.inRoadmap(second, 2, "둘", "계획", List.of()));
		testClock.advance(Duration.ofSeconds(1));
		var third = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.COMPLETED));
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(third, 1, "첫", "계획", List.of()), CourseStatus.COMPLETED));
		long otherId = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		roadmapRepository.save(RoadmapFixtures.roadmap(otherId));
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.get().uri("/api/roadmaps").exchange();

		// then
		result.expectStatus().isOk().expectBody().json("""
			{"data":{"roadmaps":[
			{"id":%d,"title":"자바 로드맵","description":"자바로 프로그램을 만들 수 있어요",
			"status":"COMPLETED","completedCourses":1,"totalCourses":1},
			{"id":%d,"title":"자바 로드맵","description":"자바로 프로그램을 만들 수 있어요",
			"status":"IN_PROGRESS","completedCourses":1,"totalCourses":2},
			{"id":%d,"title":"자바 로드맵","description":"자바로 프로그램을 만들 수 있어요",
			"status":"REVIEWING","completedCourses":0,"totalCourses":1}]}}
			""".formatted(third.getId(), second.getId(), first.getId()), JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "목록={0}")
	@ValueSource(strings = {"courses", "roadmaps"})
	@DisplayName("코스와 로드맵이 없는 사람은 이름 있는 빈 목록을 받는다")
	void returnsEmptyLists(String resource) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var client = testRequests.asUser(userId);

		// when
		var result = client.get().uri("/api/" + resource).exchange();

		// then
		result.expectStatus().isOk().expectBody()
			.json("{\"data\":{\"" + resource + "\":[]}}", JsonCompareMode.STRICT);
	}

	private List<?> state() {
		return List.of("users", "courses", "lessons", "roadmaps", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
