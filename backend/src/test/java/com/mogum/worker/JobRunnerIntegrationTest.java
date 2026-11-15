package com.mogum.worker;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.*;
import com.mogum.course.repository.*;
import com.mogum.generation.*;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.*;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestJobGenerator;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobRunnerIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @Autowired CreditLedger ledger;
    @Autowired JobIntakeService intake;
    @Autowired GenerationJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired CreditEntryRepository entries;
    @Autowired CourseRepository courses;
    @Autowired RoadmapRepository roadmaps;
    @Autowired LessonRepository lessons;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<JobGenerator> generators;
    @Autowired WorkerProperties configured;
    ScheduledExecutorService renewals;
    static final Duration LEASE = Duration.ofMinutes(5);

    @BeforeEach
    void prepareSchedulerAndStubs() {
        renewals = Executors.newSingleThreadScheduledExecutor();
        generators.forEach(generator -> ((TestJobGenerator) generator).reset());
    }

    @AfterEach
    void stopScheduler() throws Exception {
        renewals.shutdownNow();
        assertThat(renewals.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        MDC.clear();
    }

    @Test
    @DisplayName("기다리는 작업이 없으면 빈손으로 끝나고 MDC를 남기지 않는다")
    void returnsFalseWithoutWork(CapturedOutput output) {
        // when
        boolean found = runner().runOnce();

        // then
        assertThat(found).isFalse();
        assertThat(output.getAll()).doesNotContain("job finished", "job lease lost", "job save failed");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("워커의 임시 설정은 application 설정 한 곳에서 읽는다")
    void bindsProvisionalSettings() {
        // when
        var values = configured;

        // then
        assertThat(values).isEqualTo(new WorkerProperties(LEASE, Duration.ofSeconds(30), 2, Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @EnumSource(JobType.class)
    @DisplayName("여섯 종류는 값 스냅샷으로 만들고 소유권 확인 뒤 결과를 저장한다")
    void savesEveryJobType(JobType type, CapturedOutput output) {
        // given
        long userId = user();
        var request = request(userId, type);
        long id = intake.receive(userId, request).id();
        clock.advance(Duration.ofSeconds(1));
        var stub = stub(type);

        // when
        boolean found = runner().runOnce();

        // then
        assertThat(found).isTrue();
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getClaimCount()).isEqualTo(1);
        assertThat(job.getStartedAt()).isEqualTo(clock.instant());
        assertThat(job.getFinishedAt()).isEqualTo(clock.instant());
        assertThat(job.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getErrorType()).isNull();
        assertThat(stub.plan(id).calls()).isEqualTo(1);
        assertThat(stub.plan(id).saves()).isEqualTo(1);
        var snapshot = stub.plan(id).snapshots().getFirst();
        assertThat(snapshot.id()).isEqualTo(id);
        assertThat(snapshot.userId()).isEqualTo(userId);
        assertThat(snapshot.type()).isEqualTo(type);
        assertThat(snapshot.targetId()).isEqualTo(request.targetId());
        assertThat(snapshot.feedback()).isEqualTo(request.feedback());
        assertThat(snapshot.claimCount()).isEqualTo(1);
        if (request.input() != null) {
            assertThat(snapshot.input()).isEqualTo(new JobSnapshot.Input("LEAKCHECK-topic", Level.OTHER,
                    "LEAKCHECK-level", Purpose.OTHER, "LEAKCHECK-purpose", "LEAKCHECK-memo"));
        } else assertThat(snapshot.input()).isNull();
        if (type == JobType.NEW_COURSE) {
            assertThat(job.getCourseId()).isNotNull();
            assertThat(courses.findById(job.getCourseId()).orElseThrow().getInput().getMemo()).isEqualTo("LEAKCHECK-memo");
            assertThat(count("courses")).isEqualTo(1);
        }
        if (type == JobType.NEW_ROADMAP) {
            assertThat(job.getRoadmapId()).isNotNull();
            assertThat(roadmaps.findById(job.getRoadmapId()).orElseThrow().getInput().getLevelOther()).isEqualTo("LEAKCHECK-level");
            assertThat(count("roadmaps")).isEqualTo(1);
        }
        if (type == JobType.MATERIAL) assertThat(count("materials")).isEqualTo(1);
        assertCredit(userId, 99, 1, 0);
        assertThat(stub.plan(id).outsideTransaction()).isTrue();
        assertThat(stub.plan(id).mdc()).contains("jobId=" + id, "userId=" + userId);
        assertThat(output.getAll().lines().filter(line -> line.contains("job finished"))).singleElement()
                .asString().contains(" INFO ", "jobId=" + id, "userId=" + userId, "job finished status=SUCCEEDED");
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @CsvSource({"PROVIDER_UNAVAILABLE,1,SUCCEEDED,2", "PROVIDER_UNAVAILABLE,2,SUCCEEDED,3",
            "PROVIDER_UNAVAILABLE,3,FAILED,3", "INVALID_OUTPUT,1,SUCCEEDED,2", "INVALID_OUTPUT,3,FAILED,3",
            "PROVIDER_REJECTED,3,FAILED,1", "INPUT_TOO_LARGE,3,FAILED,1"})
    @DisplayName("일시적과 형식 실패만 같은 꺼내기 안에서 정한 횟수까지 다시 만든다")
    void retriesOnlyRecoverableFailures(GenerationOutcome.Failure failure, int failures, JobStatus status, int calls,
                                       CapturedOutput output) {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).fail(failure, failures);
        clock.advance(Duration.ofSeconds(1));

        // when
        runner().runOnce();

        // then
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getClaimCount()).isEqualTo(1);
        assertThat(plan.calls()).isEqualTo(calls);
        assertThat(plan.snapshots()).extracting(JobSnapshot::claimCount).containsOnly(1);
        assertThat(job.getErrorType()).isEqualTo(status == JobStatus.FAILED ? JobErrorType.valueOf(failure.name()) : null);
        assertThat(job.getFinishedAt()).isEqualTo(clock.instant());
        assertThat(job.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(count("courses")).isEqualTo(status == JobStatus.SUCCEEDED ? 1 : 0);
        assertCredit(userId, status == JobStatus.SUCCEEDED ? 99 : 100, 1, status == JobStatus.FAILED ? 1 : 0);
        assertThat(output.getAll()).contains("job finished status=" + status).doesNotContain("job lease lost", "job save failed", "LEAKCHECK");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("자료 만들기가 실패하면 자료 행이 없고 크레딧은 한 번만 복구한다")
    void restoresFailedMaterialOnlyOnce() {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.MATERIAL)).id();
        stub(JobType.MATERIAL).plan(id).fail(GenerationOutcome.Failure.INVALID_OUTPUT, 10);
        var runner = runner();

        // when
        runner.runOnce();

        // then
        assertThat(runner.runOnce()).isFalse();
        assertThat(count("materials")).isZero();
        assertCredit(userId, 100, 1, 1);
    }

    @Test
    @DisplayName("동시에 꺼내는 여러 워커는 작업마다 한 번만 집는다")
    void claimsEachJobOnceConcurrently() throws Exception {
        // given
        var ids = IntStream.range(0, 8).mapToObj(i -> {
            long userId = user("worker" + i + "@example.com");
            return intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        }).toList();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8).mapToObj(i -> workers.submit(() -> {
                start.await();
                return runner().runOnce();
            })).toList();

            // when
            start.countDown();

            // then
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
        }
        for (long id : ids) {
            assertThat(jobs.findById(id).orElseThrow().getClaimCount()).isEqualTo(1);
            assertThat(stub(JobType.NEW_COURSE).plan(id).calls()).isEqualTo(1);
        }
        assertThat(count("courses")).isEqualTo(8);
    }

    @Test
    @DisplayName("잠긴 맨 앞 작업은 기다리지 않고 다음 작업을 집는다")
    void skipsLockedHead() throws Exception {
        // given
        long userId = user();
        long first = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        long second = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        try (var connection = jdbc.getDataSource().getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM generation_jobs WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, first);
                lock.executeQuery().close();
            }
            try {
                // when
                boolean found = worker.submit(() -> runner().runOnce()).get(5, TimeUnit.SECONDS);

                // then
                assertThat(found).isTrue();
                assertThat(jobs.findById(first).orElseThrow().getClaimCount()).isZero();
                assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            } finally { connection.rollback(); }
        }
    }

    @Test
    @DisplayName("받은 시각과 ID 순으로 집으며 되찾는 작업도 원래 순서를 지킨다")
    void claimsInOriginalOrder() {
        // given
        long userId = user();
        long first = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        clock.advance(Duration.ofSeconds(1));
        long second = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        long third = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        GenerationJobFixtures.running(jdbc, first, 9, clock.instant(), clock.instant().minusSeconds(1));
        var runner = runner();

        // when
        runner.runOnce();

        // then
        assertThat(jobs.findById(first).orElseThrow().getClaimCount()).isEqualTo(10);
        assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(runner.runOnce()).isTrue();
        assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.findById(third).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertCredit(userId, 97, 3, 0);
    }

    @Test
    @DisplayName("사용자와 종류에 관계없이 받은 시각을 ID보다 먼저 비교한다")
    void ordersByCreationTimeBeforeIdAcrossUsersAndTypes() {
        // given
        long firstUser = user();
        long firstId = intake.receive(firstUser, request(firstUser, JobType.NEW_COURSE)).id();
        long secondUser = user("second@example.com");
        long earlier = intake.receive(secondUser, request(secondUser, JobType.NEW_ROADMAP)).id();
        jdbc.update("UPDATE generation_jobs SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), java.sql.Timestamp.from(clock.instant()), earlier);

        // when
        runner().runOnce();

        // then
        assertThat(jobs.findById(earlier).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.findById(firstId).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    @DisplayName("자동 다시 시도 횟수가 0이면 첫 일시적 실패에서 복구하고 끝낸다")
    void honorsZeroRetryBudget() {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).fail(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, 10);
        var runner = new JobRunner(em, transactions, ledger, clock,
                new WorkerProperties(LEASE, Duration.ofHours(1), 0, Duration.ZERO), generators, renewals);

        // when
        runner.runOnce();

        // then
        assertThat(plan.calls()).isEqualTo(1);
        assertThat(jobs.findById(id).orElseThrow().getErrorType()).isEqualTo(JobErrorType.PROVIDER_UNAVAILABLE);
        assertCredit(userId, 100, 1, 1);
    }

    @Test
    @DisplayName("시한과 같은 시각은 되찾지 않고 지난 뒤에만 꺼낸다")
    void reclaimsOnlyAfterLeaseExpires() {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        GenerationJobFixtures.running(jdbc, id, 1, clock.instant(), clock.instant());
        var runner = runner();
        assertThat(runner.runOnce()).isFalse();
        clock.advance(Duration.ofNanos(1000));

        // when
        runner.runOnce();

        // then
        assertThat(jobs.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
        assertThat(stub(JobType.NEW_COURSE).plan(id).snapshots().getFirst().claimCount()).isEqualTo(2);
        assertCredit(userId, 99, 1, 0);
    }

    @Test
    @DisplayName("오래 걸려도 주기적으로 시한과 수정 시각을 늘려 다른 워커가 집지 못한다")
    void renewsLeaseDuringGeneration(CapturedOutput output) throws Exception {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).holdFirst();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> runner(Duration.ofMillis(5)).runOnce());
            try {
                plan.awaitEntered();
                clock.advance(Duration.ofMinutes(4));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(jobs.findById(id).orElseThrow().getLeaseUntil()).isEqualTo(clock.instant().plus(LEASE)));
                clock.advance(Duration.ofMinutes(2));

                // when
                boolean claimed = runner().runOnce();

                // then
                assertThat(claimed).isFalse();
                assertThat(jobs.findById(id).orElseThrow().getUpdatedAt()).isAfter(TestClock.INITIAL_INSTANT);
                assertThat(plan.calls()).isEqualTo(1);
            } finally { plan.release(); }
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(output.getAll()).doesNotContain("job lease lost");
        assertThat(renewals.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)).isNullOrEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationOutcome.Failure.class, names = {"PROVIDER_REJECTED", "PROVIDER_UNAVAILABLE"})
    @DisplayName("되찾은 새 워커가 끝난 뒤 옛 워커의 성공과 실패는 결과와 복구를 쓰지 않는다")
    void fencesLateWorker(GenerationOutcome.Failure failure, CapturedOutput output) throws Exception {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).holdFirst().fail(failure, 1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> runner().runOnce());
            try {
                plan.awaitEntered();
                clock.advance(LEASE.plusSeconds(1));
                runner().runOnce();
                var finished = jdbc.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id);

                // when
                plan.release();
                old.get(5, TimeUnit.SECONDS);

                // then
                assertThat(jdbc.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id)).isEqualTo(finished);
                assertThat(plan.calls()).isEqualTo(2);
                assertThat(plan.saves()).isEqualTo(1);
                assertThat(count("courses")).isEqualTo(1);
                assertThat(jobs.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
                assertCredit(userId, 99, 1, 0);
            } finally { plan.release(); }
        }
        assertLeaseLostOnce(output, id, userId);
    }

    @Test
    @DisplayName("옛 워커의 늦은 성공은 결과 저장 전에 소유권을 확인해 버린다")
    void fencesLateSuccess(CapturedOutput output) throws Exception {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).holdFirst();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> runner().runOnce());
            try {
                plan.awaitEntered();
                clock.advance(LEASE.plusSeconds(1));
                runner().runOnce();
                // when
                plan.release();
                old.get(5, TimeUnit.SECONDS);
                // then
                assertThat(plan.saves()).isEqualTo(1);
                assertThat(count("courses")).isEqualTo(1);
                assertCredit(userId, 99, 1, 0);
            } finally { plan.release(); }
        }
        assertLeaseLostOnce(output, id, userId);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    @DisplayName("작업을 지우면 늘리기와 성공과 실패 기록이 결과도 복구도 쓰지 않고 멈춘다")
    void stopsAfterDeletion(boolean fail, boolean renew, CapturedOutput output) throws Exception {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).holdFirst();
        if (fail) plan.fail(GenerationOutcome.Failure.PROVIDER_REJECTED, 1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> runner(renew ? Duration.ofMillis(5) : Duration.ofHours(1)).runOnce());
            try {
                plan.awaitEntered();
                jdbc.update("DELETE FROM generation_jobs WHERE id = ?", id);
                if (renew) await().atMost(Duration.ofSeconds(5)).until(() -> output.getAll().contains("job lease lost"));

                // when
                plan.release();
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();

                // then
                assertThat(plan.saves()).isZero();
                assertThat(count("courses")).isZero();
                assertThat(jobs.findById(id)).isEmpty();
                assertCredit(userId, 99, 1, 0);
            } finally { plan.release(); }
        }
        assertLeaseLostOnce(output, id, userId);
        assertThat(output.getAll()).doesNotContain("job save failed", "job finished");
    }

    @Test
    @DisplayName("저장 중 예외는 결과와 성공을 롤백하고 시한 뒤 처음부터 되찾는다")
    void rollsBackSaveAndReclaimsWithoutRefund(CapturedOutput output) {
        // given
        long userId = user();
        long id = intake.receive(userId, request(userId, JobType.NEW_COURSE)).id();
        var plan = stub(JobType.NEW_COURSE).plan(id).failSave("LEAKCHECK-exception");
        var runner = runner();
        clock.advance(Duration.ofSeconds(1));

        // when
        runner.runOnce();

        // then
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getCourseId()).isNull();
        assertThat(count("courses")).isZero();
        assertCredit(userId, 99, 1, 0);
        assertThat(output.getAll().lines().filter(line -> line.contains("job save failed"))).singleElement()
                .asString().contains(" ERROR ", "jobId=" + id, "userId=" + userId);
        assertThat(output.getAll()).contains("at com.mogum.support.IntegrationTestConfiguration$TestJobGenerator.save")
                .doesNotContain("LEAKCHECK", "job finished");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        plan.failSave(null);
        clock.advance(LEASE.plusSeconds(1));
        assertThat(runner.runOnce()).isTrue();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
        assertThat(plan.calls()).isEqualTo(2);
        assertThat(count("courses")).isEqualTo(1);
        assertCredit(userId, 99, 1, 0);
    }

    @Test
    @DisplayName("호출자 트랜잭션이 있으면 만들기를 시작하지 않아 커넥션을 붙잡지 않는다")
    void rejectsAmbientTransaction() {
        // given
        var runner = runner();
        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> runner.runOnce()))
                .isInstanceOf(IllegalStateException.class);
    }

    private JobRunner runner() { return runner(Duration.ofHours(1)); }
    private JobRunner runner(Duration interval) {
        return new JobRunner(em, transactions, ledger, clock,
                new WorkerProperties(LEASE, interval, 2, Duration.ofMillis(1)), generators, renewals);
    }

    private TestJobGenerator stub(JobType type) {
        return (TestJobGenerator) generators.stream().filter(generator -> generator.type() == type).findFirst().orElseThrow();
    }
    private long user() { return user("worker@example.com"); }
    private long user(String email) {
        var user = users.save(UserFixtures.approved(email, 100, clock.instant().plus(Duration.ofDays(30))));
        entries.save(CreditEntry.refill(user, 100));
        return user.getId();
    }
    private JobRequest request(long userId, JobType type) {
        var input = new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER, "LEAKCHECK-purpose", "LEAKCHECK-memo");
        Long target = switch (type) {
            case NEW_COURSE, NEW_ROADMAP -> null;
            case REPLAN_COURSE -> courses.save(Course.single(userId, input, "코스")).getId();
            case REPLAN_ROADMAP -> roadmaps.save(Roadmap.plan(userId, input, "로드맵", "설명")).getId();
            case PLAN_ROADMAP_COURSE -> {
                var roadmap = roadmaps.save(Roadmap.plan(userId, input, "로드맵", "설명"));
                yield courses.save(Course.inRoadmap(roadmap, 1, "코스", "설명", List.of())).getId();
            }
            case MATERIAL -> {
                var course = courses.save(Course.single(userId, input, "코스"));
                yield lessons.save(Lesson.listed(course, 1, "강의", "설명", List.of())).getId();
            }
        };
        return new JobRequest(type, target, type == JobType.NEW_COURSE || type == JobType.NEW_ROADMAP ? input : null,
                type == JobType.REPLAN_COURSE || type == JobType.REPLAN_ROADMAP ? "LEAKCHECK-feedback" : null);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private void assertCredit(long userId, int balance, int consumes, int restores) {
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM credit_entries WHERE user_id = ?", Integer.class, userId)).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ? AND type = 'CONSUME'", Integer.class, userId)).isEqualTo(consumes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ? AND type = 'RESTORE'", Integer.class, userId)).isEqualTo(restores);
    }
    private void assertLeaseLostOnce(CapturedOutput output, long id, long userId) {
        assertThat(output.getAll().lines().filter(line -> line.contains("job lease lost"))).singleElement()
                .asString().contains(" WARN ", "jobId=" + id, "userId=" + userId);
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
    }
}
