package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.CreditProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobResult;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class ReplanCourseIntakeHandlerIntegrationTest {
	@Autowired
	JobIntakeService jobIntakeService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	CreditProperties creditProperties;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"REPLAN_COURSE", "REPLAN_ROADMAP"})
	@DisplayName("검토 중 다시 짜기를 HTTP로 받아 피드백을 작업에만 담고 크레딧을 쓴다")
	void acceptsReviewingTarget(JobType type) {
		// given
		long userId = user("me@example.com");
		long target = target(userId, type, "REVIEWING");
		var before = content();
		var client = testRequests.asUser(userId);
		var body = Map.of("type", type, type == JobType.REPLAN_COURSE ? "courseId" : "roadmapId", target,
			"feedback", "더 짧게");

		// when
		var response = client.post().uri("/api/jobs").body(body).exchange();

		// then
		response.expectStatus().isOk().expectBody()
			.jsonPath("$.data.type").isEqualTo(type.name()).jsonPath("$.data.status").isEqualTo("QUEUED");
		assertThat(content()).isEqualTo(before);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs"))
			.containsEntry("type", type.name()).containsEntry("feedback", "더 짧게");
		assertThat(balance(userId)).isEqualTo(100 - cost(type));
	}

	@ParameterizedTest(name = "종류={0}, 상태={1}")
	@CsvSource({"REPLAN_COURSE,LEARNING", "REPLAN_COURSE,COMPLETED", "REPLAN_COURSE,NOT_PLANNED",
		"REPLAN_ROADMAP,IN_PROGRESS", "REPLAN_ROADMAP,COMPLETED"})
	@DisplayName("검토 중이 아니면 공통 오류 409로 거절하고 아무것도 바꾸지 않는다")
	void rejectsOtherStates(JobType type, String status) {
		// given
		long userId = user("me@example.com");
		long target = target(userId, type, status);
		var before = state();
		var client = testRequests.asUser(userId);

		// when
		var response = client.post().uri("/api/jobs")
			.body(Map.of("type", type, type == JobType.REPLAN_COURSE ? "courseId" : "roadmapId", target))
			.exchange();

		// then
		response.expectStatus().isEqualTo(409).expectBody()
			.jsonPath("$.code").isEqualTo("REPLAN_NOT_ALLOWED")
			.jsonPath("$.message").isEqualTo("검토 중일 때만 다시 짤 수 있어요");
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "종류={0}, 기존 작업={1}")
	@CsvSource({"REPLAN_COURSE,QUEUED", "REPLAN_COURSE,RUNNING", "REPLAN_COURSE,FAILED",
		"REPLAN_ROADMAP,QUEUED", "REPLAN_ROADMAP,RUNNING", "REPLAN_ROADMAP,FAILED"})
	@DisplayName("성공하지 않은 다시 짜기는 같은 작업을 돌려주고 피드백과 크레딧을 보존한다")
	void returnsExistingUnsuccessfulJob(JobType type, JobStatus status) {
		// given
		long userId = user("me@example.com");
		long target = target(userId, type, "REVIEWING");
		var first = jobIntakeService.receive(userId, request(type, target));
		if (status == JobStatus.RUNNING) {
			GenerationJobFixtures.running(jdbcTemplate, first.id(), 1, testClock.instant(),
				testClock.instant().plusSeconds(360));
		} else if (status == JobStatus.FAILED) {
			GenerationJobFixtures.failed(jdbcTemplate, first.id(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		}
		var before = state();

		// when
		var second = jobIntakeService.receive(userId, new JobRequest(type, target, null, "새 피드백"));

		// then
		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.status()).isEqualTo(status);
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"REPLAN_COURSE", "REPLAN_ROADMAP"})
	@DisplayName("같은 대상 동시 받기는 작업 하나와 크레딧 사용 하나만 만든다")
	void deduplicatesConcurrentIntake(JobType type) throws Exception {
		// given
		long userId = user("me@example.com");
		long target = target(userId, type, "REVIEWING");
		var entered = new CountDownLatch(2);
		var executor = Executors.newFixedThreadPool(2);
		try {
			// when
			var futures = new TransactionTemplate(platformTransactionManager).execute(tx -> {
				jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
				Callable<JobResult> receive = () -> {
					entered.countDown();
					return jobIntakeService.receive(userId, request(type, target));
				};
				var first = executor.submit(receive);
				var second = executor.submit(receive);
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return List.of(first, second);
			});
			var first = futures.get(0).get(10, TimeUnit.SECONDS);
			var second = futures.get(1).get(10, TimeUnit.SECONDS);

			// then
			assertThat(first.id()).isEqualTo(second.id());
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'CONSUME'",
				Integer.class)).isEqualTo(1);
			assertThat(balance(userId)).isEqualTo(100 - cost(type));
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@ParameterizedTest(name = "종류={0}, 대상={1}")
	@CsvSource({"REPLAN_COURSE,foreign", "REPLAN_COURSE,missing",
		"REPLAN_ROADMAP,foreign", "REPLAN_ROADMAP,missing"})
	@DisplayName("남의 대상과 없는 대상은 없는 것으로 답하고 DB를 보존한다")
	void hidesForeignAndMissingTargets(JobType type, String kind) {
		// given
		long userId = user("me@example.com");
		long target = kind.equals("foreign") ? target(user("other@example.com"), type, "REVIEWING") : 999999L;
		var before = state();

		// when & then
		assertThatThrownBy(() -> jobIntakeService.receive(userId, request(type, target)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThat(state()).isEqualTo(before);
	}

	private long user(String email) {
		var user = userRepository.save(UserFixtures.approved(email, 100,
			testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return user.getId();
	}

	private long target(long userId, JobType type, String status) {
		if (type == JobType.REPLAN_ROADMAP) {
			var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId),
				RoadmapStatus.valueOf(status)));
			for (int i = 1; i <= 2; i++) {
				courseRepository.save(CourseFixtures.inRoadmap(roadmap, i, "계획-" + i, "설명", List.of()));
			}
			return roadmap.getId();
		}
		var courseStatus = CourseStatus.valueOf(status);
		var course = courseStatus == CourseStatus.NOT_PLANNED
			? CourseFixtures.inRoadmap(roadmapRepository.save(RoadmapFixtures.roadmap(userId)), 1,
				"짜기 전", "설명", List.of())
			: CourseFixtures.single(userId);
		CourseFixtures.withStatus(course, courseStatus);
		courseRepository.save(course);
		if (courseStatus != CourseStatus.NOT_PLANNED)
			lessonRepository.save(LessonFixtures.lesson(course, 1));
		return course.getId();
	}

	private JobRequest request(JobType type, long target) {
		return new JobRequest(type, target, null, "피드백");
	}

	private List<?> content() {
		return List.of("roadmaps", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}

	private List<?> state() {
		return List.of("users", "roadmaps", "courses", "lessons", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}

	private int balance(long userId) {
		return userRepository.findById(userId).orElseThrow().getCreditBalance();
	}

	private int cost(JobType type) {
		return type == JobType.REPLAN_COURSE ? creditProperties.replanCourse() : creditProperties.replanRoadmap();
	}
}
