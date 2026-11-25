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
import org.junit.jupiter.params.provider.ValueSource;
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
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.service.CourseDeletionService;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.MaterialGenerator;
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
class JobRunnerCourseDeletionIntegrationTest {
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
	CourseDeletionService courseDeletionService;
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

	@ParameterizedTest(name = "워커 먼저={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("실제 자료 저장과 코스 지우기는 작업 행부터 잠가 두 선후 순서 모두 교착 없이 끝난다")
	void serializesRealMaterialSaveAndDeletion(boolean workerFirst, CapturedOutput output) throws Exception {
		// given
		var lesson = lesson();
		long userId = lesson.getCourse().getUserId();
		long jobId = jobIntakeService.receive(userId, new JobRequest(JobType.MATERIAL, lesson.getId(), null, null))
			.id();
		var entered = new CountDownLatch(1);
		var releaseWorker = new CountDownLatch(1);
		var deleteHeld = new CountDownLatch(1);
		var releaseDelete = new CountDownLatch(1);
		var workerPid = new AtomicInteger();
		var deletePid = new AtomicInteger();
		var saves = new AtomicInteger();
		var generator = generator(workerFirst, entered, releaseWorker, workerPid, saves);
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
						courseDeletionService.delete(userId, lesson.getCourse().getId());
						deleteHeld.countDown();
						awaitLatch(releaseDelete);
					}));
				if (workerFirst) {
					await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
						"SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, workerPid.get(), deletePid.get()))
						.isTrue());
					assertThat(deletion.isDone()).isFalse();
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
				assertRemainingState(userId, jobId, workerFirst ? 3 : 0, workerFirst ? 99 : 100);
				assertThat(output.getAll()).doesNotContain("job save failed", "deadlock detected");
			} finally {
				releaseWorker.countDown();
				releaseDelete.countDown();
			}
		}
	}

	@Test
	@DisplayName("호출 뒤 코스를 지우면 늦은 워커는 저장을 멈추고 호출 기록만 작업 연결 없이 남긴다")
	void fencesLateMaterialResultAfterDeletion(CapturedOutput output) throws Exception {
		// given
		var lesson = lesson();
		long userId = lesson.getCourse().getUserId();
		long jobId = jobIntakeService.receive(userId, new JobRequest(JobType.MATERIAL, lesson.getId(), null, null))
			.id();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var saves = new AtomicInteger();
		try (var scheduler = Executors.newSingleThreadScheduledExecutor();
			var executor = Executors.newSingleThreadExecutor()) {
			var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
				workerProperties, List.of(generator(false, entered, release, new AtomicInteger(), saves)), scheduler);
			var worker = executor.submit(runner::runOnce);
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				courseDeletionService.delete(userId, lesson.getCourse().getId());
				// when
				release.countDown();
				assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
				// then
				assertThat(saves.get()).isZero();
				assertRemainingState(userId, jobId, 0, 100);
				assertThat(runner.runOnce()).isFalse();
				assertThat(output.getAll()).contains("job lease lost").doesNotContain("job save failed");
			} finally {
				release.countDown();
			}
		}
	}

	private JobGenerator generator(boolean holdSave, CountDownLatch entered, CountDownLatch release,
		AtomicInteger pid, AtomicInteger saves) {
		return new JobGenerator() {
			@Override
			public JobType type() {
				return JobType.MATERIAL;
			}

			@Override
			public GenerationOutcome generate(JobSnapshot job) {
				var result = materialGenerator.generate(job);
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
				return materialGenerator.save(job, result);
			}
		};
	}

	private void assertRemainingState(long userId, long jobId, int cards, int balance) {
		for (var table : List.of("generation_jobs", "materials", "lessons", "courses")) {
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cards", Integer.class)).isEqualTo(cards);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM cards WHERE status <> 'COURSE_DELETED' OR lesson_id IS NOT NULL", Integer.class))
			.isZero();
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(balance);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls WHERE job_id IS NOT NULL",
			Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls", String.class)).containsOnly("SUCCEEDED");
		testRequests.asUser(userId).get().uri("/api/jobs/" + jobId).exchange().expectStatus().isNotFound();
	}

	private Lesson lesson() {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var course = courseRepository
			.save(CourseFixtures.withStatus(CourseFixtures.single(user.getId()), CourseStatus.LEARNING));
		return lessonRepository.save(LessonFixtures.lesson(course, 1));
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
