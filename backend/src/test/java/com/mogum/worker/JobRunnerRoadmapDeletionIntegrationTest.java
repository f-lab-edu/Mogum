package com.mogum.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.course.service.RoadmapDeletionService;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.MaterialGenerator;
import com.mogum.generation.PlanRoadmapCourseGenerator;
import com.mogum.generation.ResultRef;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

import jakarta.persistence.EntityManager;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobRunnerRoadmapDeletionIntegrationTest {
	@Autowired
	EntityManager entityManager;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	WorkerProperties workerProperties;
	@Autowired
	MaterialGenerator materialGenerator;
	@Autowired
	PlanRoadmapCourseGenerator planRoadmapCourseGenerator;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	RoadmapDeletionService roadmapDeletionService;
	@Autowired
	JobIntakeService jobIntakeService;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;

	@ParameterizedTest(name = "워커 먼저={0}, 종류={1}")
	@CsvSource({"true,MATERIAL", "false,MATERIAL", "true,PLAN_ROADMAP_COURSE", "false,PLAN_ROADMAP_COURSE"})
	@DisplayName("뒤 코스의 실제 결과 저장과 로드맵 지우기는 모든 작업부터 잠가 두 선후 순서 모두 교착 없이 끝난다")
	void serializesRealSaveAndDeletion(boolean workerFirst, JobType type, CapturedOutput output) throws Exception {
		// given
		var target = target(type);
		long userId = target.userId();
		long jobId = jobIntakeService
			.receive(userId,
				new JobRequest(type, type == JobType.MATERIAL ? target.lessonId() : target.courseId(), null,
					null))
			.id();
		var entered = new CountDownLatch(1);
		var releaseWorker = new CountDownLatch(1);
		var deleteHeld = new CountDownLatch(1);
		var releaseDelete = new CountDownLatch(1);
		var workerPid = new AtomicInteger();
		var deletePid = new AtomicInteger();
		var saves = new AtomicInteger();
		var generator = generator(type, workerFirst, entered, releaseWorker, workerPid, saves);
		try (var scheduler = Executors.newSingleThreadScheduledExecutor();
			var executor = Executors.newFixedThreadPool(2)) {
			var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
				workerProperties, List.of(generator), scheduler);
			var worker = executor.submit(runner::runOnce);
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				// when
				var deletion = executor.submit(() -> new TransactionTemplate(platformTransactionManager)
					.executeWithoutResult(tx -> {
						deletePid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
						roadmapDeletionService.delete(userId, target.roadmapId());
						deleteHeld.countDown();
						awaitLatch(releaseDelete);
					}));
				if (workerFirst) {
					await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
						"SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, workerPid.get(), deletePid.get()))
						.isTrue());
					assertThat(deletion.isDone()).isFalse();
					// 뒤 코스의 작업을 기다리는 동안에도 사용자 행은 아직 잡지 않았다.
					new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> jdbcTemplate
						.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", Long.class, userId));
					releaseWorker.countDown();
					assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
					assertThat(deleteHeld.await(10, TimeUnit.SECONDS)).isTrue();
				} else {
					assertThat(deleteHeld.await(10, TimeUnit.SECONDS)).isTrue();
					releaseWorker.countDown();
					await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
						"SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
						Integer.class, deletePid.get())).isPositive());
					assertThat(worker.isDone()).isFalse();
				}
				releaseDelete.countDown();
				deletion.get(10, TimeUnit.SECONDS);
				assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
				// then
				assertThat(saves.get()).isEqualTo(workerFirst ? 1 : 0);
				assertRemainingState(target, jobId, workerFirst && type == JobType.MATERIAL ? 3 : 0,
					workerFirst ? 99 : 100);
				assertThat(output.getAll()).doesNotContain("job save failed", "deadlock detected");
			} finally {
				releaseWorker.countDown();
				releaseDelete.countDown();
			}
		}
	}

	@Test
	@DisplayName("로드맵 지우기 뒤 늦은 코스 강의 목록 결과는 코스와 로드맵 및 강의를 되살리지 않는다")
	void fencesLateCoursePlanAfterDeletion(CapturedOutput output) throws Exception {
		// given
		var target = target(JobType.PLAN_ROADMAP_COURSE);
		long userId = target.userId();
		long jobId = jobIntakeService
			.receive(userId, new JobRequest(JobType.PLAN_ROADMAP_COURSE, target.courseId(), null, null))
			.id();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var saves = new AtomicInteger();
		try (var scheduler = Executors.newSingleThreadScheduledExecutor();
			var executor = Executors.newSingleThreadExecutor()) {
			var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
				workerProperties,
				List.of(generator(JobType.PLAN_ROADMAP_COURSE, false, entered, release, new AtomicInteger(), saves)),
				scheduler);
			var worker = executor.submit(runner::runOnce);
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				roadmapDeletionService.delete(userId, target.roadmapId());
				// when
				release.countDown();
				assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
				// then
				assertThat(saves.get()).isZero();
				assertRemainingState(target, jobId, 0, 100);
				assertThat(runner.runOnce()).isFalse();
				assertThat(output.getAll()).contains("job lease lost").doesNotContain("job save failed");
			} finally {
				release.countDown();
			}
		}
	}

	private JobGenerator generator(JobType type, boolean holdSave, CountDownLatch entered, CountDownLatch release,
		AtomicInteger pid, AtomicInteger saves) {
		return new JobGenerator() {
			@Override
			public JobType type() {
				return type;
			}

			@Override
			public GenerationOutcome generate(JobSnapshot job) {
				var result = delegate().generate(job);
				if (!holdSave) {
					entered.countDown();
					awaitLatch(release);
				}
				return result;
			}

			@Override
			public ResultRef save(JobSnapshot job, GeneratedResult result) {
				saves.incrementAndGet();
				if (holdSave) {
					pid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
					entered.countDown();
					awaitLatch(release);
				}
				return delegate().save(job, result);
			}

			private JobGenerator delegate() {
				return type == JobType.MATERIAL ? materialGenerator : planRoadmapCourseGenerator;
			}
		};
	}

	private void assertRemainingState(Target target, long jobId, int cards, int balance) {
		long userId = target.userId();
		for (var table : List.of("generation_jobs", "materials", "lessons", "courses", "roadmaps")) {
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cards", Integer.class)).isEqualTo(cards);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM cards WHERE status <> 'COURSE_DELETED' OR lesson_id IS NOT NULL", Integer.class))
			.isZero();
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(balance);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isPositive();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls WHERE job_id IS NOT NULL",
			Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls", String.class)).containsOnly("SUCCEEDED");
		var client = testRequests.asUser(userId);
		for (var uri : List.of("/api/jobs/" + jobId, "/api/roadmaps/" + target.roadmapId(),
			"/api/courses/" + target.courseId())) {
			client.get().uri(uri).exchange().expectStatus().isNotFound()
				.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		}
	}

	private Target target(JobType type) {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(user.getId()), RoadmapStatus.IN_PROGRESS));
		// 먼저 지울 코스도 두어 코스마다 작업 → 사용자 잠금을 반복하는 구현을 잡는다.
		courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "첫 코스", "계획", List.of()), CourseStatus.COMPLETED));
		var course = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 2, "다음 코스", "계획", List.of()),
			type == JobType.MATERIAL ? CourseStatus.LEARNING : CourseStatus.NOT_PLANNED));
		Long lessonId = type == JobType.MATERIAL
			? lessonRepository.save(LessonFixtures.lesson(course, 1)).getId() : null;
		return new Target(user.getId(), roadmap.getId(), course.getId(), lessonId);
	}

	private record Target(long userId, long roadmapId, long courseId, Long lessonId) {
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
