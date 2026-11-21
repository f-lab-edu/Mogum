package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class CourseCommandServiceIntegrationTest {
	@Autowired
	CourseCommandService courseCommandService;
	@Autowired
	JobIntakeService jobIntakeService;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("시작은 검토 중만 전환하고 시작했던 코스는 그대로 돌려주며 짜기 전은 거절한다")
	void startsOrReturnsCurrentCourse(CourseStatus status) {
		// given
		long userId = user();
		var course = status == CourseStatus.NOT_PLANNED
			? CourseFixtures.inRoadmap(roadmapRepository.save(RoadmapFixtures.roadmap(userId)),
				1, "첫", "계획", List.of())
			: CourseFixtures.withStatus(CourseFixtures.single(userId), status);
		courseRepository.save(course);
		if (status != CourseStatus.NOT_PLANNED)
			lessonRepository.save(LessonFixtures.lesson(course, 1));
		var client = testRequests.asUser(userId);
		var before = state();
		testClock.advance(Duration.ofSeconds(5));

		// when
		var result = client.post().uri("/api/courses/" + course.getId() + "/start").exchange();

		// then
		if (status == CourseStatus.NOT_PLANNED) {
			result.expectStatus().isEqualTo(409).expectBody()
				.jsonPath("$.code").isEqualTo("COURSE_START_NOT_ALLOWED");
			assertThat(state()).isEqualTo(before);
		} else {
			result.expectStatus().isOk().expectBody().jsonPath("$.data.status")
				.isEqualTo(status == CourseStatus.REVIEWING ? "LEARNING" : status.name());
			var saved = courseRepository.findById(course.getId()).orElseThrow();
			assertThat(saved.getStartedAt())
				.isEqualTo(status == CourseStatus.REVIEWING ? testClock.instant() : course.getStartedAt());
			assertThat(saved.getLastLessonOpenedAt()).isNull();
			if (status == CourseStatus.REVIEWING) {
				assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
			} else {
				assertThat(state()).isEqualTo(before);
			}
		}
	}

	@ParameterizedTest(name = "다시 짜기={0}")
	@ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED", "DISCARDED"})
	@DisplayName("기다림과 만드는 중과 실패한 다시 짜기는 시작을 막고 성공 또는 버린 뒤에는 시작한다")
	void blocksOnlyUnfinishedReplan(String status) {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.single(userId));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		long jobId = jobIntakeService.receive(userId, new JobRequest(JobType.REPLAN_COURSE,
			course.getId(), null, null)).id();
		if (status.equals("RUNNING")) {
			GenerationJobFixtures.running(jdbcTemplate, jobId, 1,
				testClock.instant(), testClock.instant().plusSeconds(360));
		} else if (status.equals("FAILED") || status.equals("DISCARDED")) {
			GenerationJobFixtures.failed(jdbcTemplate, jobId, JobErrorType.INVALID_OUTPUT, testClock.instant());
			if (status.equals("DISCARDED"))
				jobIntakeService.discard(userId, jobId);
		} else if (status.equals("SUCCEEDED")) {
			GenerationJobFixtures.succeeded(jdbcTemplate, jobId, course.getId(), null, null, testClock.instant());
		}
		var client = testRequests.asUser(userId);
		var before = state();

		// when
		var result = client.post().uri("/api/courses/" + course.getId() + "/start").exchange();

		// then
		if (status.equals("SUCCEEDED") || status.equals("DISCARDED")) {
			result.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("LEARNING");
		} else {
			result.expectStatus().isEqualTo(409).expectBody()
				.jsonPath("$.code").isEqualTo("REPLAN_IN_PROGRESS")
				.jsonPath("$.message").isEqualTo("다시 짜는 중에는 시작할 수 없어요");
			assertThat(state()).isEqualTo(before);
		}
	}

	@ParameterizedTest(name = "먼저 받기={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("시작과 다시 짜기 받기는 같은 사용자 행 잠금에서 기다려 먼저 통과한 한 동작만 성공한다")
	void serializesStartAndReplanIntake(boolean startFirst) throws Exception {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.single(userId));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		var executor = Executors.newSingleThreadExecutor();
		var entered = new CountDownLatch(1);
		try {
			// when
			var future = new TransactionTemplate(platformTransactionManager).execute(tx -> {
				creditLedger.lock(userId);
				Callable<ErrorCode> loser = () -> {
					entered.countDown();
					try {
						if (startFirst)
							jobIntakeService.receive(userId, replan(course.getId()));
						else
							courseCommandService.start(userId, course.getId());
						return null;
					} catch (ApiException e) {
						return e.errorCode();
					}
				};
				var pending = executor.submit(loser);
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
					assertThat(jdbcTemplate.queryForObject("""
						SELECT count(*) FROM pg_stat_activity
						WHERE datname = current_database() AND wait_event_type = 'Lock'
						""", Integer.class)).isPositive();
					assertThat(pending.isDone()).isFalse();
				});
				if (startFirst)
					courseCommandService.start(userId, course.getId());
				else
					jobIntakeService.receive(userId, replan(course.getId()));
				return pending;
			});
			var rejection = future.get(10, TimeUnit.SECONDS);

			// then
			assertThat(rejection).isEqualTo(startFirst
				? ErrorCode.REPLAN_NOT_ALLOWED : ErrorCode.REPLAN_IN_PROGRESS);
			assertThat(courseRepository.findById(course.getId()).orElseThrow().getStatus())
				.isEqualTo(startFirst ? CourseStatus.LEARNING : CourseStatus.REVIEWING);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class))
				.isEqualTo(startFirst ? 0 : 1);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'CONSUME'",
				Integer.class)).isEqualTo(startFirst ? 0 : 1);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private JobRequest replan(long courseId) {
		return new JobRequest(JobType.REPLAN_COURSE, courseId, null, null);
	}

	private long user() {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return user.getId();
	}

	private List<?> state() {
		return List.of("users", "courses", "lessons", "roadmaps", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
