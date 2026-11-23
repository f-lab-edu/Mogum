package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.MaterialFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class LessonReadServiceIntegrationTest {
	@Autowired
	LessonReadService lessonReadService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;

	@ParameterizedTest(name = "자료={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("열 수 있는 강의 읽기는 본문 유무에 맞춰 반환하고 코스의 마지막으로 연 때만 적는다")
	void readsWithoutGenerating(boolean material) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var course = course(userId);
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		if (material) {
			materialRepository.save(MaterialFixtures.material(lesson, "# 강의 본문"));
		}
		var client = testRequests.asUser(userId);
		var before = unchangedState();
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = client.get().uri("/api/lessons/" + lesson.getId()).exchange();
		// then
		result.expectStatus().isOk().expectBody().jsonPath("$.data.id").isEqualTo(lesson.getId())
			.jsonPath("$.data.courseId").isEqualTo(course.getId()).jsonPath("$.data.completed").isEqualTo(false)
			.jsonPath("$.data.job").doesNotExist();
		if (material) {
			result.expectBody().jsonPath("$.data.material.body").isEqualTo("# 강의 본문");
		} else {
			result.expectBody().jsonPath("$.data.material").doesNotExist();
		}
		assertThat(unchangedState()).isEqualTo(before);
		var saved = courseRepository.findById(course.getId()).orElseThrow();
		assertThat(saved.getLastLessonOpenedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getStatus()).isEqualTo(CourseStatus.LEARNING);
		assertThat(saved.getCompletedAt()).isNull();
	}

	@ParameterizedTest(name = "작업={0}")
	@ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
	@DisplayName("자료가 없으면 같은 강의의 작업 ID와 상태를 읽고 작업과 크레딧은 바꾸지 않는다")
	void readsMaterialJobs(String status) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var lesson = lessonRepository.save(LessonFixtures.lesson(course(userId), 1));
		var other = lessonRepository.save(LessonFixtures.lesson(lesson.getCourse(), 2));
		generationJobRepository.save(GenerationJobFixtures.material(userId, other.getId()));
		var job = generationJobRepository.save(GenerationJobFixtures.material(userId, lesson.getId()));
		if (status.equals("RUNNING")) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
				testClock.instant().plusSeconds(960));
		} else if (status.equals("FAILED")) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		} else if (status.equals("SUCCEEDED")) {
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), null, null, lesson.getId(), testClock.instant());
		}
		var before = unchangedState();
		// when
		var result = lessonReadService.read(userId, lesson.getId());
		// then
		assertThat(result.material()).isNull();
		assertThat(result.job().id()).isEqualTo(job.getId());
		assertThat(result.job().status()).isEqualTo(status);
		assertThat(unchangedState()).isEqualTo(before);
	}

	@Test
	@DisplayName("완료한 강의를 다시 읽으면 본문과 완료 여부를 반환하고 작업은 생략한다")
	void rereadsCompletedMaterial() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var course = courseRepository
			.save(CourseFixtures.withStatus(CourseFixtures.single(userId), CourseStatus.COMPLETED));
		var lesson = lessonRepository
			.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1), testClock.instant()));
		materialRepository.save(MaterialFixtures.material(lesson, "본문"));
		generationJobRepository.save(GenerationJobFixtures.material(userId, lesson.getId()));
		var before = unchangedState();
		// when
		var result = lessonReadService.read(userId, lesson.getId());
		// then
		assertThat(result.completed()).isTrue();
		assertThat(result.material().body()).isEqualTo("본문");
		assertThat(result.job()).isNull();
		assertThat(unchangedState()).isEqualTo(before);
		assertThat(courseRepository.findById(course.getId()).orElseThrow().getCompletedAt())
			.isEqualTo(course.getCompletedAt());
	}

	@ParameterizedTest(name = "경우={0}")
	@ValueSource(strings = {"FOREIGN", "MISSING", "REVIEWING", "PREVIOUS"})
	@DisplayName("남의 강의와 없는 강의 및 열 수 없는 강의는 거절하고 마지막으로 연 때도 적지 않는다")
	void rejectsInaccessibleLessons(String scenario) {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		long owner = scenario.equals("FOREIGN") ? userRepository.save(UserFixtures.user("other@example.com")).getId()
			: userId;
		var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(owner),
			scenario.equals("REVIEWING") ? CourseStatus.REVIEWING : CourseStatus.LEARNING));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 2));
		long id = scenario.equals("MISSING") ? 999999L : scenario.equals("PREVIOUS") ? lesson.getId()
			: lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).getFirst().getId();
		var client = testRequests.asUser(userId);
		var before = unchangedState();
		var courses = jdbcTemplate.queryForList("SELECT * FROM courses");
		// when
		var result = client.get().uri("/api/lessons/" + id).exchange();
		// then
		boolean hidden = scenario.equals("FOREIGN") || scenario.equals("MISSING");
		result.expectStatus().isEqualTo(hidden ? 404 : 409).expectBody()
			.jsonPath("$.code").isEqualTo(hidden ? "NOT_FOUND" : "LESSON_NOT_OPEN");
		assertThat(unchangedState()).isEqualTo(before);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM courses")).isEqualTo(courses);
	}

	private Course course(long userId) {
		return courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(userId), CourseStatus.LEARNING));
	}

	private List<?> unchangedState() {
		return List
			.of("users", "lessons", "materials", "generation_jobs", "credit_entries", "cards", "reviews", "roadmaps")
			.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
