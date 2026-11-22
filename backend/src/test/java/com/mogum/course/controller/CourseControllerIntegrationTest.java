package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CourseControllerIntegrationTest {
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestRequests testRequests;
	@Autowired
	TestClock testClock;
	@Autowired
	JdbcIndexedSessionRepository jdbcIndexedSessionRepository;

	@ParameterizedTest(name = "동작={0}, 대상={1}")
	@CsvSource({"course,foreign", "course,missing", "roadmap,foreign", "roadmap,missing",
		"start,foreign", "start,missing", "rename,foreign", "rename,missing"})
	@DisplayName("남의 코스와 로드맵 및 없는 대상은 같은 404이고 DB는 바뀌지 않는다")
	void hidesForeignAndMissingResources(String action, String kind) {
		// given
		long userId = approved("me@example.com");
		long otherId = approved("other@example.com");
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(otherId));
		var course = courseRepository.save(CourseFixtures.single(otherId));
		long id = kind.equals("missing") ? 999999L
			: action.equals("roadmap") ? roadmap.getId() : course.getId();
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = operation(client, action, id);

		// then
		result.expectStatus().isNotFound().expectBody().json(
			"{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}", JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
	}

	@Test
	@DisplayName("코스 목록은 본인의 만들어진 단일 코스와 로드맵 코스만 최근 생성 순으로 읽는다")
	void listsOwnedGeneratedCoursesNewestFirst() {
		// given
		long userId = approved("me@example.com");
		var first = courseRepository.save(CourseFixtures.single(userId));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var second = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "로드맵 코스", "설명", List.of()), CourseStatus.LEARNING));
		courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "짜기 전", "설명", List.of()));
		testClock.advance(Duration.ofSeconds(1));
		var third = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.single(userId), CourseStatus.COMPLETED));
		courseRepository.save(CourseFixtures.single(approved("other@example.com")));
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.get().uri("/api/courses").exchange();

		// then
		result.expectStatus().isOk().expectBody().json("""
			{"data":{"courses":[{"id":%d,"title":"자바 기초","status":"COMPLETED"},
			{"id":%d,"title":"로드맵 코스","status":"LEARNING"},
			{"id":%d,"title":"자바 기초","status":"REVIEWING"}]}}
			""".formatted(third.getId(), second.getId(), first.getId()), JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("코스 하나는 상태와 강의 완료 및 진도를 읽고 입력과 시각 및 상태를 바꾸지 않는다")
	void readsCourseWithoutChangingData(CourseStatus status) {
		// given
		long userId = approved("me@example.com");
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var course = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "코스", "계획", List.of()), status));
		Long nextLessonId = null;
		if (status != CourseStatus.NOT_PLANNED) {
			lessonRepository.save(LessonFixtures.withCompletedAt(
				LessonFixtures.lesson(course, 1), testClock.instant()));
			var lesson = LessonFixtures.lesson(course, 2);
			if (status == CourseStatus.COMPLETED) {
				LessonFixtures.withCompletedAt(lesson, testClock.instant());
			}
			nextLessonId = lessonRepository.save(lesson).getId();
		}
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.get().uri("/api/courses/" + course.getId()).exchange();

		// then
		var body = result.expectStatus().isOk().expectBody()
			.jsonPath("$.data.roadmapId").isEqualTo(roadmap.getId())
			.jsonPath("$.data.status").isEqualTo(status.name())
			.jsonPath("$.data.progress.totalLessons").isEqualTo(status == CourseStatus.NOT_PLANNED ? 0 : 2)
			.jsonPath("$.data.progress.completedLessons").isEqualTo(status == CourseStatus.NOT_PLANNED ? 0
				: status == CourseStatus.COMPLETED ? 2 : 1)
			.jsonPath("$.data.userId").doesNotExist().jsonPath("$.data.input").doesNotExist()
			.jsonPath("$.data.topic").doesNotExist().jsonPath("$.data.masteryReport").doesNotExist()
			.jsonPath("$.data.startedAt").doesNotExist().jsonPath("$.data.createdAt").doesNotExist();
		if (status == CourseStatus.LEARNING) {
			body.jsonPath("$.data.nextLessonId").isEqualTo(nextLessonId);
		} else {
			body.jsonPath("$.data.nextLessonId").doesNotExist();
		}
		if (status == CourseStatus.COMPLETED) {
			body.jsonPath("$.data.completedAt").isEqualTo("2026-11-02T00:00:00Z");
		} else {
			body.jsonPath("$.data.completedAt").doesNotExist();
		}
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("어떤 상태든 100 코드 포인트 제목으로 고치며 입력과 계획은 유지하고 수정 시각을 갱신한다")
	void renamesAtCodePointBoundary(CourseStatus status) {
		// given
		long userId = approved("me@example.com");
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var course = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "이전 제목", "계획 설명", List.of()), status));
		var before = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId());
		testClock.advance(Duration.ofSeconds(5));
		var client = testRequests.asUser(userId);
		var title = "😀".repeat(100);

		// when
		var result = client.patch().uri("/api/courses/" + course.getId()).body(Map.of("title", title)).exchange();

		// then
		result.expectStatus().isOk().expectBody().jsonPath("$.data.title").isEqualTo(title);
		var saved = courseRepository.findById(course.getId()).orElseThrow();
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getCreatedAt()).isEqualTo(course.getCreatedAt());
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId()))
			.usingRecursiveComparison().ignoringFields("title", "updated_at").isEqualTo(before);
	}

	@ParameterizedTest(name = "제목={0}")
	@ValueSource(strings = {"LONG", "BLANK", "MISSING", "MALFORMED"})
	@DisplayName("잘못된 제목 요청은 칸 오류나 공통 오류이고 원문을 응답과 로그에 남기지 않는다")
	void rejectsInvalidTitleWithoutLeaking(String kind, CapturedOutput output) {
		// given
		long userId = approved("me@example.com");
		var course = courseRepository.save(CourseFixtures.single(userId));
		var client = testRequests.asUser(userId);
		var marker = "LEAKCHECK-course-title";
		var json = switch (kind) {
			case "LONG" -> "{\"title\":\"" + marker + "😀".repeat(101) + "\"}";
			case "BLANK" -> "{\"title\":\" \\t\\n\"}";
			case "MISSING" -> "{}";
			default -> "{\"title\":\"" + marker;
		};
		var before = state();

		// when
		var result = client.patch().uri("/api/courses/" + course.getId())
			.contentType(MediaType.APPLICATION_JSON).body(json).exchange();

		// then
		result.expectStatus().isBadRequest().expectBody().json(kind.equals("MALFORMED") ? """
			{"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
			""" : """
			{"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
			"fields":[{"field":"title","reason":"%s"}]}
			""".formatted(kind.equals("LONG") ? "TOO_LONG" : "REQUIRED"), JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
		assertThat(output.getAll()).doesNotContain(marker, "Unhandled API exception");
	}

	@ParameterizedTest(name = "동작={0}")
	@ValueSource(strings = {"start", "rename"})
	@DisplayName("시작과 제목 고치기는 CSRF 토큰 없이 거절한다")
	void requiresCsrf(String action) {
		// given
		long userId = approved("me@example.com");
		var course = courseRepository.save(CourseFixtures.single(userId));
		var cookie = new TestSessions(jdbcIndexedSessionRepository).create(userId);
		var client = testRequests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();
		var before = state();

		// when
		var result = operation(client, action, course.getId());

		// then
		result.expectStatus().isForbidden().expectBody().json("""
			{"code":"CSRF_INVALID","message":"요청을 확인할 수 없습니다"}
			""", JsonCompareMode.STRICT);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "계정={0}")
	@ValueSource(strings = {"NOT_APPLIED", "APPLIED", "REJECTED", "ANONYMOUS"})
	@DisplayName("읽기 넷과 보내기 둘은 승인되지 않은 사람을 모두 막는다")
	void requiresApproval(String status) {
		// given
		RestTestClient client;
		if (status.equals("NOT_APPLIED")) {
			var cookie = new TestSessions(jdbcIndexedSessionRepository).createNotApplied(
				new AccountPrincipal.NotApplied("google", "subject", "me@example.com", "나"));
			var sessionClient = testRequests.anonymous().mutate()
				.defaultCookie(cookie.getName(), cookie.getValue()).build();
			var token = sessionClient.get().uri("/api/me").exchange().returnResult()
				.getResponseCookies().getFirst("XSRF-TOKEN");
			client = sessionClient.mutate().defaultCookie("XSRF-TOKEN", token.getValue())
				.defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
		} else if (status.equals("ANONYMOUS")) {
			client = testRequests.anonymous();
		} else {
			var user = userRepository.save(UserFixtures.withStatus(
				UserFixtures.user("me@example.com"), UserStatus.valueOf(status)));
			client = testRequests.asUser(user.getId());
		}
		var before = state();

		// when & then
		for (var action : List.of("courses", "roadmaps", "course", "roadmap", "start", "rename")) {
			operation(client, action, 999L).expectStatus().isEqualTo(status.equals("ANONYMOUS") ? 401 : 403)
				.expectBody().json(status.equals("ANONYMOUS") ? """
					{"code":"UNAUTHORIZED","message":"로그인이 필요합니다"}
					""" : """
					{"code":"ACCOUNT_NOT_APPROVED","message":"승인된 계정이 필요합니다"}
					""", JsonCompareMode.STRICT);
		}
		assertThat(state()).isEqualTo(before);
	}

	private long approved(String email) {
		return userRepository.save(UserFixtures.approved(email)).getId();
	}

	private RestTestClient.ResponseSpec operation(RestTestClient client, String action, long id) {
		return switch (action) {
			case "course" -> client.get().uri("/api/courses/" + id).exchange();
			case "roadmap" -> client.get().uri("/api/roadmaps/" + id).exchange();
			case "courses", "roadmaps" -> client.get().uri("/api/" + action).exchange();
			case "start" -> client.post().uri("/api/courses/" + id + "/start").exchange();
			case "rename" -> client.patch().uri("/api/courses/" + id).body(Map.of("title", "새 제목")).exchange();
			default -> throw new IllegalArgumentException("Unknown operation");
		};
	}

	private List<?> state() {
		return List.of("users", "courses", "lessons", "roadmaps", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
