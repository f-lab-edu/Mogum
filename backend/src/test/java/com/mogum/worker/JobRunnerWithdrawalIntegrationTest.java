package com.mogum.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
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
import com.mogum.account.service.WithdrawService;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.MaterialGenerator;
import com.mogum.generation.ResultRef;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

import jakarta.persistence.EntityManager;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobRunnerWithdrawalIntegrationTest {
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
	WithdrawService withdrawService;
	@Autowired
	LlmCallWriter llmCallWriter;
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

	@ParameterizedTest(name = "워커 먼저={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("실제 자료 저장과 탈퇴는 작업 행부터 잠가 두 선후 순서 모두 교착 없이 끝난다")
	void serializesRealMaterialSaveAndWithdrawal(boolean workerFirst, CapturedOutput output) throws Exception {
		// given
		var lesson = lesson();
		long userId = lesson.getCourse().getUserId();
		jobIntakeService.receive(userId, new JobRequest(JobType.MATERIAL, lesson.getId(), null, null));
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
						withdrawService.withdraw(userId);
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
				assertRemainingState(userId, 2);
				assertThat(output.getAll()).doesNotContain("job save failed", "deadlock detected");
			} finally {
				releaseWorker.countDown();
				releaseDelete.countDown();
			}
		}
	}

	@ParameterizedTest(name = "늦은 실패={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("탈퇴 뒤 늦은 성공과 실패는 아무것도 쓰지 않고 진행 중과 뒤늦게 시작한 호출 기록을 연결 없이 보존한다")
	void fencesLateResultAndPreservesCallsAfterWithdrawal(boolean failure, CapturedOutput output) throws Exception {
		// given
		var lesson = lesson();
		long userId = lesson.getCourse().getUserId();
		jobIntakeService.receive(userId, new JobRequest(JobType.MATERIAL, lesson.getId(), null, null));
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var saves = new AtomicInteger();
		var generator = new JobGenerator() {
			@Override
			public JobType type() {
				return JobType.MATERIAL;
			}

			@Override
			public GenerationOutcome generate(JobSnapshot job) {
				var result = materialGenerator.generate(job);
				long pending = llmCallWriter.start(job.id(), job.userId(), job.type(), LlmCallType.CONTINUATION,
					"test-model");
				entered.countDown();
				awaitLatch(release);
				llmCallWriter.finish(pending, LlmCallOutcome.SUCCEEDED, 11, 7, new BigDecimal("0.01"), 20);
				long late = llmCallWriter.start(job.id(), job.userId(), job.type(), LlmCallType.EMBEDDING,
					"test-model");
				llmCallWriter.finish(late, LlmCallOutcome.SUCCEEDED, 13, null, new BigDecimal("0.02"), 30);
				return failure ? GenerationOutcome.Failure.PROVIDER_REJECTED : result;
			}

			@Override
			public ResultRef save(JobSnapshot job, GeneratedResult result) {
				saves.incrementAndGet();
				return materialGenerator.save(job, result);
			}
		};
		try (var scheduler = Executors.newSingleThreadScheduledExecutor();
			var executor = Executors.newSingleThreadExecutor()) {
			var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
				workerProperties, List.of(generator), scheduler);
			var worker = executor.submit(runner::runOnce);
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls WHERE outcome IS NULL",
					Integer.class)).isEqualTo(1);
				withdrawService.withdraw(userId);
				// when
				release.countDown();
				assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
				// then
				assertThat(saves.get()).isZero();
				assertRemainingState(userId, 4);
				assertThat(jdbcTemplate.queryForList("SELECT input_tokens FROM llm_calls WHERE model = 'test-model' "
					+ "ORDER BY id", Integer.class)).containsExactly(11, 13);
				assertThat(jdbcTemplate.queryForList("SELECT duration_ms FROM llm_calls WHERE model = 'test-model' "
					+ "ORDER BY id", Integer.class)).containsExactly(20, 30);
				assertThat(runner.runOnce()).isFalse();
				assertThat(output.getAll()).contains("job lease lost")
					.doesNotContain("job save failed", "job finished status=FAILED", "deadlock detected");
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

	private void assertRemainingState(long userId, int calls) {
		for (var table : List.of("generation_jobs", "reviews", "card_concepts", "cards", "materials", "lessons",
			"courses", "roadmaps", "concepts", "credit_entries", "users")) {
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
		}
		assertThat(userRepository.findById(userId)).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isEqualTo(calls);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls "
			+ "WHERE job_id IS NOT NULL OR user_id IS NOT NULL", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls", String.class)).containsOnly("SUCCEEDED");
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
