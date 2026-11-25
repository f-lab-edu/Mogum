package com.mogum.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.dto.JobResponse;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class JobReadServiceIntegrationTest {
	@Autowired
	JobReadService jobReadService;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("홈 작업은 본인의 기다림과 만드는 중과 실패만 받은 시각과 ID의 내림차순으로 읽는다")
	void listsOnlyOwnedUnfinishedJobsInReceiveOrder() {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		long other = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		var now = testClock.instant();
		var oldest = generationJobRepository.save(GenerationJobFixtures.newCourse(owner, input));
		testClock.set(now.plusSeconds(20));
		var running = generationJobRepository.save(GenerationJobFixtures.newCourse(owner, input));
		GenerationJobFixtures.running(jdbcTemplate, running.getId(), 2, now, now.plusSeconds(100));
		testClock.set(now.plusSeconds(10));
		var failed = generationJobRepository.save(GenerationJobFixtures.newRoadmap(owner, input));
		GenerationJobFixtures.failed(jdbcTemplate, failed.getId(), JobErrorType.INVALID_OUTPUT, now.plusSeconds(50));
		testClock.set(now.plusSeconds(20));
		var tied = generationJobRepository.save(GenerationJobFixtures.newRoadmap(owner, input));
		testClock.set(now.plusSeconds(30));
		var succeeded = generationJobRepository.save(GenerationJobFixtures.newCourse(owner, input));
		var course = courseRepository.save(CourseFixtures.single(owner));
		GenerationJobFixtures.succeeded(jdbcTemplate, succeeded.getId(), course.getId(), null, null, now);
		generationJobRepository.save(GenerationJobFixtures.newCourse(other, input));

		// when
		var result = jobReadService.unfinished(owner);

		// then
		assertThat(result).extracting(JobResponse::id)
			.containsExactly(tied.getId(), running.getId(), failed.getId(), oldest.getId());
		assertThat(result).extracting(JobResponse::status)
			.containsExactly(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.FAILED, JobStatus.QUEUED);
	}

	@Test
	@DisplayName("홈 작업이 없으면 빈 목록을 반환한다")
	void listsNoJobsForEmptyAccount() {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();

		// when
		var result = jobReadService.unfinished(owner);

		// then
		assertThat(result).isEmpty();
	}

	@ParameterizedTest(name = "종류={0}, 상태={1}")
	@MethodSource("typesAndStates")
	@DisplayName("홈 작업 항목은 작업 하나 읽기의 입력 요약과 현재 제목과 실패 오류를 그대로 쓰며 성공은 제외한다")
	void matchesSingleReadForEveryTypeAndStatus(JobType type, JobStatus status) {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var input = new LearningInput("물리", Level.OTHER, "수학은 알아요", Purpose.OTHER, "연구", "비공개 메모");
		var course = courseRepository.save(CourseFixtures.single(owner));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(owner));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var job = generationJobRepository.save(switch (type) {
			case NEW_COURSE -> GenerationJobFixtures.newCourse(owner, input);
			case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(owner, input);
			case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(owner, course.getId());
			case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(owner, course.getId(), "비공개 피드백");
			case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(owner, roadmap.getId(), "비공개 피드백");
			case MATERIAL -> GenerationJobFixtures.material(owner, lesson.getId());
		});
		var now = testClock.instant();
		if (status == JobStatus.RUNNING) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 3, now, now.plusSeconds(100));
		} else if (status == JobStatus.FAILED) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(),
				type == JobType.NEW_COURSE ? JobErrorType.INPUT_TOO_LARGE : JobErrorType.PROVIDER_REJECTED, now);
		} else if (status == JobStatus.SUCCEEDED) {
			boolean courseResult = type == JobType.NEW_COURSE || type == JobType.PLAN_ROADMAP_COURSE
				|| type == JobType.REPLAN_COURSE;
			boolean roadmapResult = type == JobType.NEW_ROADMAP || type == JobType.REPLAN_ROADMAP;
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), courseResult ? course.getId() : null,
				roadmapResult ? roadmap.getId() : null, type == JobType.MATERIAL ? lesson.getId() : null, now);
		}
		jdbcTemplate.update("UPDATE courses SET title = ? WHERE id = ?", "현재 코스 제목", course.getId());
		jdbcTemplate.update("UPDATE roadmaps SET title = ? WHERE id = ?", "현재 로드맵 제목", roadmap.getId());
		jdbcTemplate.update("UPDATE lessons SET title = ? WHERE id = ?", "현재 강의 제목", lesson.getId());
		var expected = jobReadService.read(owner, job.getId());
		var jobsBefore = jdbcTemplate.queryForList("SELECT * FROM generation_jobs ORDER BY id");
		var usersBefore = jdbcTemplate.queryForList("SELECT * FROM users ORDER BY id");
		var creditsBefore = jdbcTemplate.queryForList("SELECT * FROM credit_entries ORDER BY id");
		var coursesBefore = jdbcTemplate.queryForList("SELECT * FROM courses ORDER BY id");
		var roadmapsBefore = jdbcTemplate.queryForList("SELECT * FROM roadmaps ORDER BY id");
		var lessonsBefore = jdbcTemplate.queryForList("SELECT * FROM lessons ORDER BY id");
		testClock.advance(Duration.ofSeconds(60));

		// when
		var result = jobReadService.unfinished(owner);

		// then
		if (status == JobStatus.SUCCEEDED) {
			assertThat(result).isEmpty();
		} else {
			assertThat(result).containsExactly(expected);
		}
		assertThat(jdbcTemplate.queryForList("SELECT * FROM generation_jobs ORDER BY id")).isEqualTo(jobsBefore);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM credit_entries ORDER BY id")).isEqualTo(creditsBefore);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM courses ORDER BY id")).isEqualTo(coursesBefore);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM roadmaps ORDER BY id")).isEqualTo(roadmapsBefore);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM lessons ORDER BY id")).isEqualTo(lessonsBefore);
	}

	static Stream<Object[]> typesAndStates() {
		return Arrays.stream(JobType.values())
			.flatMap(type -> Arrays.stream(JobStatus.values())
				.map(status -> new Object[] {type, status}));
	}

	@ParameterizedTest(name = "종류={0}, 상태={1}")
	@MethodSource("typesAndStates")
	@DisplayName("여섯 종류의 모든 상태를 요약과 성공 결과 또는 실패 묶음으로 읽고 DB를 보존한다")
	void readsAllTypesAndStates(JobType type, JobStatus status) {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var input = new LearningInput("물리", Level.OTHER, "수학은 알아요", Purpose.OTHER, "연구", "비공개 메모");
		var course = courseRepository.save(CourseFixtures.single(owner));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(owner));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));

		var job = generationJobRepository.save(switch (type) {
			case NEW_COURSE -> GenerationJobFixtures.newCourse(owner, input);
			case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(owner, input);
			case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(owner, course.getId());
			case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(owner, course.getId(), "비공개 피드백");
			case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(owner, roadmap.getId(), "비공개 피드백");
			case MATERIAL -> GenerationJobFixtures.material(owner, lesson.getId());
		});

		var now = Instant.parse("2026-11-15T00:00:00Z");
		switch (status) {
			// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
			case QUEUED -> {}
			// @checkstyle:on
			case RUNNING -> GenerationJobFixtures.running(jdbcTemplate, job.getId(), 3, now, now.plusSeconds(100));
			case FAILED -> GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.PROVIDER_REJECTED, now);
			case SUCCEEDED -> GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), switch (type) {
				case NEW_COURSE, PLAN_ROADMAP_COURSE, REPLAN_COURSE -> course.getId();
				default -> null;
			}, switch (type) {
				// @checkstyle:off — 포매터의 두 번째 switch 인자 들여쓰기를 따른다.
				case NEW_ROADMAP, REPLAN_ROADMAP -> roadmap.getId();
				default -> null;
			},
				// @checkstyle:on
				type == JobType.MATERIAL ? lesson.getId() : null, now);
		}

		var before = jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", job.getId());

		// when
		var result = jobReadService.read(owner, job.getId());

		// then
		assertThat(result.id()).isEqualTo(job.getId());
		assertThat(result.type()).isEqualTo(type);
		assertThat(result.status()).isEqualTo(status);

		if (type == JobType.NEW_COURSE || type == JobType.NEW_ROADMAP) {
			assertThat(result.subject().topic()).isEqualTo("물리");
			assertThat(result.subject().level()).isEqualTo("OTHER");
			assertThat(result.subject().levelOther()).isEqualTo("수학은 알아요");
			assertThat(result.subject().purpose()).isEqualTo("OTHER");
			assertThat(result.subject().purposeOther()).isEqualTo("연구");
			assertThat(result.subject().title()).isNull();
		} else {
			assertThat(result.subject().topic()).isNull();
			assertThat(result.subject().title()).isEqualTo(switch (type) {
				case MATERIAL -> lesson.getTitle();
				case REPLAN_ROADMAP -> roadmap.getTitle();
				default -> course.getTitle();
			});
		}

		if (status == JobStatus.SUCCEEDED) {
			assertThat(result.result().type()).isEqualTo(switch (type) {
				case NEW_ROADMAP, REPLAN_ROADMAP -> "ROADMAP";
				case MATERIAL -> "LESSON";
				default -> "COURSE";
			});
			assertThat(result.result().id()).isEqualTo(switch (type) {
				case NEW_ROADMAP, REPLAN_ROADMAP -> roadmap.getId();
				case MATERIAL -> lesson.getId();
				default -> course.getId();
			});
			assertThat(result.result().courseId()).isEqualTo(type == JobType.MATERIAL ? course.getId() : null);
		} else {
			assertThat(result.result()).isNull();
		}

		if (status == JobStatus.FAILED) {
			assertThat(result.error().code()).isEqualTo("JOB_TEMPORARY_FAILURE");
			assertThat(result.error().message()).isEqualTo("만들지 못했습니다. 다시 시도하거나 버려 주세요");
		} else {
			assertThat(result.error()).isNull();
		}

		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", job.getId()))
			.isEqualTo(before);
	}

	static Stream<Object[]> typesAndErrors() {
		return Arrays.stream(JobType.values()).flatMap(type -> Arrays.stream(JobErrorType.values())
			.map(error -> new Object[] {type, error}));
	}

	@ParameterizedTest(name = "종류={0}, 실패 까닭={1}")
	@MethodSource("typesAndErrors")
	@DisplayName("실패 까닭 넷을 사용자 행동이 같은 두 묶음으로 반환한다")
	void groupsFailureCauses(JobType type, JobErrorType error) {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		var course = courseRepository.save(CourseFixtures.single(owner));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(owner));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));

		var job = generationJobRepository.save(switch (type) {
			case NEW_COURSE -> GenerationJobFixtures.newCourse(owner, input);
			case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(owner, input);
			case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(owner, course.getId());
			case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(owner, course.getId(), null);
			case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(owner, roadmap.getId(), null);
			case MATERIAL -> GenerationJobFixtures.material(owner, lesson.getId());
		});
		GenerationJobFixtures.failed(jdbcTemplate, job.getId(), error, Instant.parse("2026-11-15T00:00:00Z"));

		// when
		var result = jobReadService.read(owner, job.getId());

		// then
		boolean inputProblem = error == JobErrorType.INPUT_TOO_LARGE;
		assertThat(result.error().code()).isEqualTo(inputProblem ? "JOB_INPUT_FAILURE" : "JOB_TEMPORARY_FAILURE");
		assertThat(result.error().message())
			.isEqualTo(inputProblem ? "주제를 좁혀 새로 만들어 주세요" : "만들지 못했습니다. 다시 시도하거나 버려 주세요");
		assertThat(result.error().fields()).isNull();
		assertThat(result.error().retryAfterSeconds()).isNull();
	}

	@Test
	@DisplayName("남의 작업과 없는 작업은 같은 오류로 반환한다")
	void rejectsForeignAndMissingJobs() {
		// given
		long owner = userRepository.save(UserFixtures.approved("owner@example.com")).getId();
		var job = generationJobRepository.save(GenerationJobFixtures.newCourse(owner,
			new LearningInput("비공개 주제", Level.BEGINNER, null, Purpose.WORK, null, null)));

		// when & then
		assertThatThrownBy(() -> jobReadService.read(owner + 1, job.getId())).isInstanceOfSatisfying(ApiException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> jobReadService.read(owner, 999L)).isInstanceOfSatisfying(ApiException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
