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
import com.mogum.generation.*;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.*;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestJobGenerator;
import com.mogum.support.IntegrationTestConfiguration.DatabaseProbe;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.*;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class WorkerLifecycleIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CreditLedger ledger;
    @Autowired TestClock clock;
    @Autowired GenerationJobRepository jobs;
    @Autowired JobIntakeService intake;
    @Autowired UserRepository users;
    @Autowired CreditEntryRepository entries;
    @Autowired List<JobGenerator> generators;
    @Autowired WorkerLifecycle configuredLifecycle;
    @Autowired WorkerProperties configured;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private WorkerLifecycle lifecycle;
    private final List<Thread> processingThreads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetGenerators() {
        generators.forEach(generator -> ((TestJobGenerator) generator).reset());
    }

    @AfterEach
    void finishWorkers() {
        if (lifecycle != null) lifecycle.stop();
        generators.forEach(generator -> {
            if (generator.type() == JobType.NEW_COURSE) {
                jdbc.queryForList("SELECT id FROM generation_jobs", Long.class)
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
        var persisted = jobs.findById(id).orElseThrow();
        // then
        assertThat(configured.count()).isZero();
        assertThat(configuredLifecycle.isRunning()).isFalse();
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
        lifecycle = lifecycle(1, Duration.ofSeconds(3), Duration.ofMillis(10));
        lifecycle.start();
        plan.awaitEntered();
        var stopped = new CountDownLatch(1);

        // when
        lifecycle.stop(stopped::countDown);

        // then
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(stopped.getCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(jobs.findById(first).orElseThrow().getLeaseUntil())
                        .isEqualTo(clock.instant().plus(Duration.ofMinutes(5))));
        plan.release();
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.findById(first).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
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
        lifecycle = lifecycle(1, Duration.ofMillis(80), Duration.ofMillis(10));
        lifecycle.start();
        plan.awaitEntered();
        clock.advance(Duration.ofSeconds(1));

        // when
        lifecycle.stop();

        // then
        var released = jobs.findById(id).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(released.getLeaseUntil()).isNull();
        assertThat(released.getClaimCount()).isEqualTo(1);
        assertThat(released.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(released.getFinishedAt()).isNull();
        assertThat(output.getAll().lines().filter(line -> line.contains("job released"))).singleElement()
                .asString().contains(" INFO ", "jobId=" + id, "userId=" + userId);
        plan.release();
        await().atMost(Duration.ofSeconds(5)).until(() -> processingThreads.stream().noneMatch(Thread::isAlive));
        assertThat(plan.calls()).isEqualTo(1);
        assertThat(plan.saves()).isZero();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class)).isZero();
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(99);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'", Integer.class)).isZero();
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
    }

    @Test
    @DisplayName("옛 claim_count의 종료는 되찾은 워커의 RUNNING을 돌려놓지 않는다")
    void doesNotReleaseAnotherClaim(CapturedOutput output) throws Exception {
        // given
        long id = job(user());
        var plan = stub().plan(id).holdFirst();
        lifecycle = lifecycle(1, Duration.ofMillis(60), Duration.ofMinutes(4));
        lifecycle.start();
        plan.awaitEntered();
        GenerationJobFixtures.running(jdbc, id, 2, clock.instant(), clock.instant().plusSeconds(300));

        // when
        lifecycle.stop();

        // then
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobs.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("job released");
        plan.release();
    }

    @Test
    @DisplayName("작업 삭제 뒤 종료해도 작업 및 결과를 되살리거나 돌려놓았다고 기록하지 않는다")
    void ignoresDeletedJobOnRelease(CapturedOutput output) throws Exception {
        // given
        long id = job(user());
        var plan = stub().plan(id).holdFirst();
        lifecycle = lifecycle(1, Duration.ofMillis(60), Duration.ofMinutes(4));
        lifecycle.start();
        plan.awaitEntered();
        jdbc.update("DELETE FROM generation_jobs WHERE id = ?", id);

        // when
        lifecycle.stop();

        // then
        assertThat(jobs.findById(id)).isEmpty();
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
        lifecycle = lifecycle(2, Duration.ofMillis(60), Duration.ofMinutes(4));

        // when
        lifecycle.start();

        // then
        firstPlan.awaitEntered();
        secondPlan.awaitEntered();
        assertThat(processingThreads).hasSize(2).doesNotHaveDuplicates()
                .allSatisfy(thread -> assertThat(thread.isVirtual()).isFalse());
        assertThat(jobs.findById(first).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobs.findById(third).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        lifecycle.stop();
        firstPlan.release();
        secondPlan.release();
    }

    @Test
    @DisplayName("DB 연결 예외 뒤에도 루프가 살아 다음 꺼내기에서 실제 작업을 끝낸다")
    void recoversPollingAfterDatabaseFailure(CapturedOutput output) {
        // given
        long id = job(user());
        var probe = (DatabaseProbe) dataSource;
        lifecycle = lifecycle(1, Duration.ofSeconds(1), Duration.ofMinutes(4));
        probe.failConnections(true);

        // when
        try {
            lifecycle.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> output.getAll().lines()
                    .filter(line -> line.contains("worker poll failed")).count() >= 2);
        } finally {
            probe.failConnections(false);
        }

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED));
        lifecycle.stop();
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
            @Override public ResultRef save(JobSnapshot job, GeneratedResult result) { return stub().save(job, result); }
        };
        return new WorkerLifecycle(settings, scheduler ->
                new JobRunner(em, transactions, ledger, clock, settings, List.of(recording), scheduler));
    }

    private TestJobGenerator stub() {
        return (TestJobGenerator) generators.stream().filter(generator -> generator.type() == JobType.NEW_COURSE)
                .findFirst().orElseThrow();
    }

    private long user() {
        var user = users.save(UserFixtures.approved("lifecycle@example.com", 100, clock.instant().plus(Duration.ofDays(30))));
        entries.save(CreditEntry.refill(user, 100));
        return user.getId();
    }

    private long job(long userId) {
        return intake.receive(userId, new JobRequest(JobType.NEW_COURSE, null,
                new LearningInput("LEAKCHECK-topic", Level.BEGINNER, null, Purpose.GENERAL, null, null), null)).id();
    }
}
