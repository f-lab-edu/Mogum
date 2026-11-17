package com.mogum.worker;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.ResultRef;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.DatabaseProbe;
import com.mogum.support.IntegrationTestConfiguration.TestJobGenerator;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class WorkerLifecycleIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager platformTransactionManager;
    @Autowired CreditLedger creditLedger;
    @Autowired TestClock testClock;
    @Autowired GenerationJobRepository generationJobRepository;
    @Autowired JobIntakeService jobIntakeService;
    @Autowired UserRepository userRepository;
    @Autowired CreditEntryRepository creditEntryRepository;
    @Autowired List<JobGenerator> generators;
    @Autowired WorkerLifecycle configuredWorkerLifecycle;
    @Autowired WorkerProperties configuredWorkerProperties;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    private WorkerLifecycle workerLifecycle;
    private final List<Thread> processingThreads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetGenerators() {
        generators.forEach(generator -> ((TestJobGenerator) generator).reset());
    }

    @AfterEach
    void finishWorkers() {
        if (workerLifecycle != null) workerLifecycle.stop();

        generators.forEach(generator -> {
            if (generator.type() == JobType.NEW_COURSE) {
                jdbcTemplate.queryForList("SELECT id FROM generation_jobs", Long.class)
                        .forEach(id -> ((TestJobGenerator) generator).plan(id).release());
            }
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> processingThreads.stream().noneMatch(Thread::isAlive));
    }

    @Test
    @DisplayName("공용 통합 컨텍스트는 워커 수 0으로 작업을 집지 않는다")
    void disablesAutomaticWorkersInSharedContext() {
        // given
        long id = job(user());

        // when
        var persisted = generationJobRepository.findById(id).orElseThrow();

        // then
        assertThat(configuredWorkerProperties.count()).isZero();
        assertThat(configuredWorkerLifecycle.isRunning()).isFalse();
        assertThat(persisted.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(persisted.getClaimCount()).isZero();
    }

    @Test
    @DisplayName("종료 중에도 시한을 늘리며 한도 안에 끝난 결과만 저장하고 다음 작업은 집지 않는다")
    void savesWithinGraceAndStopsClaiming() throws Exception {
        // given
        long userId = user();
        long first = job(userId);
        long second = job(userId);
        var plan = stub().plan(first).holdFirst();

        workerLifecycle = lifecycle(1, Duration.ofSeconds(3), Duration.ofMillis(10));
        workerLifecycle.start();
        plan.awaitEntered();
        var stopped = new CountDownLatch(1);

        // when
        workerLifecycle.stop(stopped::countDown);

        // then
        assertThat(workerLifecycle.isRunning()).isFalse();
        assertThat(stopped.getCount()).isEqualTo(1);

        testClock.advance(Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(generationJobRepository.findById(first).orElseThrow().getLeaseUntil())
                        .isEqualTo(testClock.instant().plus(Duration.ofMinutes(5))));

        plan.release();
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(generationJobRepository.findById(first).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(generationJobRepository.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(stub().plan(second).calls()).isZero();
        assertThat(processingThreads).hasSize(1).allSatisfy(thread -> assertThat(thread.isVirtual()).isFalse());
    }

    @ParameterizedTest(name = "늦은 실패={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("한도를 넘기면 기다림으로 돌려놓고 늦은 성공과 실패는 결과 및 크레딧을 쓰지 않는다")
    void releasesAndFencesLateAttempt(boolean failure, CapturedOutput output) throws Exception {
        // given
        long userId = user();
        long id = job(userId);
        var plan = stub().plan(id).holdFirst();
        if (failure) plan.fail(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, 10);

        workerLifecycle = lifecycle(1, Duration.ofMillis(80), Duration.ofMillis(10));
        workerLifecycle.start();
        plan.awaitEntered();
        testClock.advance(Duration.ofSeconds(1));

        // when
        workerLifecycle.stop();

        // then
        var released = generationJobRepository.findById(id).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(released.getLeaseUntil()).isNull();
        assertThat(released.getClaimCount()).isEqualTo(1);
        assertThat(released.getUpdatedAt()).isEqualTo(testClock.instant());
        assertThat(released.getFinishedAt()).isNull();
        assertThat(output.getAll().lines().filter(line -> line.contains("job released")))
                .singleElement()
                .asString()
                .contains(" INFO ", "jobId=" + id, "userId=" + userId);

        plan.release();
        await().atMost(Duration.ofSeconds(5)).until(() -> processingThreads.stream().noneMatch(Thread::isAlive));

        assertThat(plan.calls()).isEqualTo(1);
        assertThat(plan.saves()).isZero();
        assertThat(generationJobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM courses", Integer.class)).isZero();
        assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(99);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'", Integer.class))
                .isZero();
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
    }

    @Test
    @DisplayName("옛 claim_count의 종료는 되찾은 워커의 RUNNING을 돌려놓지 않는다")
    void doesNotReleaseAnotherClaim(CapturedOutput output) throws Exception {
        // given
        long id = job(user());
        var plan = stub().plan(id).holdFirst();

        workerLifecycle = lifecycle(1, Duration.ofMillis(60), Duration.ofMinutes(4));
        workerLifecycle.start();
        plan.awaitEntered();
        GenerationJobFixtures.running(jdbcTemplate, id, 2, testClock.instant(), testClock.instant().plusSeconds(300));

        // when
        workerLifecycle.stop();

        // then
        assertThat(generationJobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(generationJobRepository.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("job released");

        plan.release();
    }

    @Test
    @DisplayName("작업 삭제 뒤 종료해도 작업 및 결과를 되살리거나 돌려놓았다고 기록하지 않는다")
    void ignoresDeletedJobOnRelease(CapturedOutput output) throws Exception {
        // given
        long id = job(user());
        var plan = stub().plan(id).holdFirst();

        workerLifecycle = lifecycle(1, Duration.ofMillis(60), Duration.ofMinutes(4));
        workerLifecycle.start();
        plan.awaitEntered();
        jdbcTemplate.update("DELETE FROM generation_jobs WHERE id = ?", id);

        // when
        workerLifecycle.stop();

        // then
        assertThat(generationJobRepository.findById(id)).isEmpty();
        assertThat(output.getAll()).doesNotContain("job released");

        plan.release();
    }

    @Test
    @DisplayName("N개의 플랫폼 스레드가 서로 다른 작업 하나씩 동시에 처리한다")
    void runsConfiguredNumberOfWorkers() throws Exception {
        // given
        long userId = user();
        long first = job(userId);
        long second = job(userId);
        long third = job(userId);
        var firstPlan = stub().plan(first).holdFirst();
        var secondPlan = stub().plan(second).holdFirst();

        workerLifecycle = lifecycle(2, Duration.ofMillis(60), Duration.ofMinutes(4));

        // when
        workerLifecycle.start();

        // then
        firstPlan.awaitEntered();
        secondPlan.awaitEntered();
        assertThat(processingThreads)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(thread -> assertThat(thread.isVirtual()).isFalse());
        assertThat(generationJobRepository.findById(first).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(generationJobRepository.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(generationJobRepository.findById(third).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);

        workerLifecycle.stop();
        firstPlan.release();
        secondPlan.release();
    }

    @Test
    @DisplayName("DB 연결 예외 뒤에도 루프가 살아 다음 꺼내기에서 실제 작업을 끝낸다")
    void recoversPollingAfterDatabaseFailure(CapturedOutput output) {
        // given
        long id = job(user());
        var probe = (DatabaseProbe) dataSource;

        workerLifecycle = lifecycle(1, Duration.ofSeconds(1), Duration.ofMinutes(4));
        probe.failConnections(true);

        // when
        try {
            workerLifecycle.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> output.getAll()
                    .lines()
                    .filter(line -> line.contains("worker poll failed"))
                    .count() >= 2);
        } finally {
            probe.failConnections(false);
        }

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(generationJobRepository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.SUCCEEDED));

        workerLifecycle.stop();
        assertThat(stub().plan(id).saves()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
    }

    private WorkerLifecycle lifecycle(int count, Duration wait, Duration renewal) {
        var settings = new WorkerProperties(Duration.ofMinutes(5), renewal, 2, Duration.ofMillis(1),
                count, Duration.ofMillis(10), wait);

        var recording = new JobGenerator() {
            @Override public JobType type() { return JobType.NEW_COURSE; }

            @Override public GenerationOutcome generate(JobSnapshot job) {
                processingThreads.add(Thread.currentThread());
                return stub().generate(job);
            }

            @Override public ResultRef save(JobSnapshot job, GeneratedResult result) {
                return stub().save(job, result);
            }
        };

        return new WorkerLifecycle(settings, scheduler ->
                new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock, settings,
                        List.of(recording), scheduler));
    }

    private TestJobGenerator stub() {
        return (TestJobGenerator) generators.stream()
                .filter(generator -> generator.type() == JobType.NEW_COURSE)
                .findFirst()
                .orElseThrow();
    }

    private long user() {
        var user = userRepository.save(UserFixtures.approved(
                "lifecycle@example.com", 100, testClock.instant().plus(Duration.ofDays(30))));
        creditEntryRepository.save(CreditEntry.refill(user, 100));
        return user.getId();
    }

    private long job(long userId) {
        return jobIntakeService.receive(userId, new JobRequest(JobType.NEW_COURSE, null,
                new LearningInput("LEAKCHECK-topic", Level.BEGINNER, null, Purpose.GENERAL, null, null), null)).id();
    }
}
