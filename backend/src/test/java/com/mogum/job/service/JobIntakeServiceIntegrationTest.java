package com.mogum.job.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.CreditProperties;
import com.mogum.config.JobProperties;
import com.mogum.course.domain.*;
import com.mogum.course.repository.*;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.*;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.IntegrationTestConfiguration.TestJobIntakeHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobIntakeServiceIntegrationTest {
    @Autowired JobIntakeService service;
    @Autowired GenerationJobRepository jobs;
    @Autowired CourseRepository courses;
    @Autowired RoadmapRepository roadmaps;
    @Autowired LessonRepository lessons;
    @Autowired UserRepository users;
    @Autowired CreditEntryRepository entries;
    @Autowired CreditLedger ledger;
    @Autowired CreditProperties credits;
    @Autowired JobProperties properties;
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @DisplayName("상한 바로 아래 두 받기가 users 행에서 기다려 하나만 받는다")
    void serializesIntakeAtLimit() throws Exception {
        // given
        long userId = user(100);
        for (int i = 0; i < properties.maxInProgressPerUser() - 1; i++) service.receive(userId, request(JobType.NEW_COURSE, userId));
        var first = request(JobType.NEW_ROADMAP, userId);
        var second = request(JobType.MATERIAL, userId);

        // when
        var results = concurrently(userId, () -> service.receive(userId, first), () -> service.receive(userId, second));

        // then
        assertThat(results.stream().filter(JobResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ApiException.class::isInstance)).singleElement()
                .satisfies(result -> assertThat(((ApiException) result).errorCode()).isEqualTo(ErrorCode.TOO_MANY_IN_PROGRESS));
        assertThat(jobCount()).isEqualTo(properties.maxInProgressPerUser());
        assertBalanceMatchesLedger(userId);
    }

    @ParameterizedTest
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("같은 강의와 코스와 로드맵의 동시 받기는 작업과 씀 하나로 같은 ID를 돌려준다")
    void deduplicatesConcurrentTargetIntake(JobType type) throws Exception {
        // given
        long userId = user(100);
        var request = request(type, userId);

        // when
        var results = concurrently(userId, () -> service.receive(userId, request), () -> service.receive(userId, request));

        // then
        assertThat(results).hasSize(2).allMatch(JobResult.class::isInstance);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(jobCount()).isEqualTo(1);
        assertThat(entryCount("CONSUME")).isEqualTo(1);
        assertBalanceMatchesLedger(userId);
    }

    @ParameterizedTest
    @EnumSource(JobType.class)
    @DisplayName("받으면 그 종류의 설정만큼 잔액을 줄이고 씀을 한 줄 남긴다")
    void recordsConfiguredCreditCost(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, userId);
        int expected = cost(type);

        // when
        var result = service.receive(userId, request);

        // then
        assertThat(result.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(100 - expected);
        assertThat(jdbc.queryForObject("SELECT amount FROM credit_entries WHERE job_id = ?", Integer.class, result.id()))
                .isEqualTo(-expected);
        assertThat(entryCount("CONSUME")).isEqualTo(1);
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("크레딧이 부족하면 작업 INSERT도 롤백하고 받기 로그를 남기지 않는다")
    void rollsBackJobWhenCreditInsufficient(CapturedOutput output) {
        // given
        long userId = user(0);
        var request = request(JobType.NEW_COURSE, userId);

        // when & then
        assertError(() -> service.receive(userId, request), ErrorCode.CREDIT_INSUFFICIENT);
        assertThat(jobCount()).isZero();
        assertThat(entryCount("CONSUME")).isZero();
        assertThat(output.getAll()).doesNotContain("job received");
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("처리기 거절은 작업과 잔액과 기록을 그대로 둔다")
    void rollsBackHandlerRejection() {
        // given
        long userId = user(100);
        var request = new JobRequest(JobType.NEW_COURSE, null,
                new LearningInput(TestJobIntakeHandler.REJECT_TOPIC, Level.BEGINNER, null, Purpose.WORK, null, null), null);

        // when & then
        assertError(() -> service.receive(userId, request), ErrorCode.INVALID_REQUEST);
        assertThat(jobCount()).isZero();
        assertThat(entryCount("CONSUME")).isZero();
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("실패한 작업의 동시 다시 시도는 한 번만 쓰며 받은 시각을 유지하고 수정 시각을 바꾼다")
    void retriesOnceAndPreservesCreationTime() throws Exception {
        // given
        long userId = user(100);
        var result = service.receive(userId, request(JobType.NEW_COURSE, userId));
        fail(result.id(), JobErrorType.INVALID_OUTPUT);
        ledger.restoreIfOwed(userId, result.id());
        var before = jobs.findById(result.id()).orElseThrow();
        clock.advance(Duration.ofMinutes(1));

        // when
        var results = concurrently(userId, () -> service.retry(userId, result.id()), () -> service.retry(userId, result.id()));

        // then
        assertThat(results).containsOnly(new JobResult(result.id(), JobStatus.QUEUED));
        var after = jobs.findById(result.id()).orElseThrow();
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(after.getErrorType()).isNull();
        assertThat(after.getFinishedAt()).isNull();
        assertThat(entryCount("CONSUME")).isEqualTo(2);
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(100 - credits.newCourse());
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("다시 시도할 잔액이 부족하면 실패 상태와 오류와 시각을 롤백한다")
    void rollsBackRetryWhenCreditInsufficient() {
        // given
        long userId = user(credits.newCourse());
        var result = service.receive(userId, request(JobType.NEW_COURSE, userId));
        fail(result.id(), JobErrorType.PROVIDER_UNAVAILABLE);
        var before = jobs.findById(result.id()).orElseThrow();
        clock.advance(Duration.ofMinutes(1));

        // when & then
        assertError(() -> service.retry(userId, result.id()), ErrorCode.CREDIT_INSUFFICIENT);
        var after = jobs.findById(result.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorType()).isEqualTo(before.getErrorType());
        assertThat(after.getFinishedAt()).isEqualTo(before.getFinishedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(entryCount("CONSUME")).isEqualTo(1);
        assertBalanceMatchesLedger(userId);
    }

    @ParameterizedTest(name = "이미 복구함 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("실패 버리기는 복구가 한 번뿐이고 모든 크레딧 기록의 작업 연결을 비운다")
    void discardsWithExactlyOneRestore(boolean alreadyRestored) {
        // given
        long userId = user(100);
        var result = service.receive(userId, request(JobType.NEW_COURSE, userId));
        fail(result.id(), JobErrorType.INPUT_TOO_LARGE);
        if (alreadyRestored) ledger.restoreIfOwed(userId, result.id());

        // when
        service.discard(userId, result.id());

        // then
        assertThat(jobs.findById(result.id())).isEmpty();
        assertThat(entryCount("RESTORE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE job_id IS NOT NULL", Integer.class)).isZero();
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("같은 실패에 다시 시도와 버리기가 겹쳐도 둘 중 먼저 한 동작만 크레딧을 움직인다")
    void serializesRetryAndDiscard() throws Exception {
        // given
        long userId = user(100);
        var result = service.receive(userId, request(JobType.NEW_COURSE, userId));
        fail(result.id(), JobErrorType.INVALID_OUTPUT);
        ledger.restoreIfOwed(userId, result.id());

        // when
        var outcomes = concurrently(userId, () -> service.retry(userId, result.id()), () -> {
            service.discard(userId, result.id());
            return "discarded";
        });

        // then
        assertThat(outcomes.stream().filter(ApiException.class::isInstance)).singleElement().satisfies(outcome ->
                assertThat(((ApiException) outcome).errorCode()).isIn(ErrorCode.NOT_FOUND, ErrorCode.JOB_DISCARD_NOT_ALLOWED));
        assertThat(entryCount("RESTORE")).isEqualTo(1);
        assertBalanceMatchesLedger(userId);
    }

    @ParameterizedTest
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("남의 강의와 코스와 로드맵에 받기는 없는 것으로 답하고 데이터를 바꾸지 않는다")
    void rejectsForeignTarget(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, user(100));

        // when & then
        assertError(() -> service.receive(userId, request), ErrorCode.NOT_FOUND);
        assertThat(jobCount()).isZero();
        assertThat(entryCount("CONSUME")).isZero();
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("남의 작업 다시 시도와 버리기는 없는 것으로 답하고 아무것도 바꾸지 않는다")
    void rejectsForeignJob() {
        // given
        long userId = user(100);
        long other = user(100);
        var result = service.receive(other, request(JobType.NEW_COURSE, other));
        fail(result.id(), JobErrorType.INVALID_OUTPUT);

        // when & then
        assertError(() -> service.retry(userId, result.id()), ErrorCode.NOT_FOUND);
        assertError(() -> service.discard(userId, result.id()), ErrorCode.NOT_FOUND);
        assertThat(jobs.findById(result.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(entryCount("CONSUME")).isEqualTo(1);
        assertThat(entryCount("RESTORE")).isZero();
        assertBalanceMatchesLedger(userId);
        assertBalanceMatchesLedger(other);
    }

    @ParameterizedTest
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("성공한 자료와 코스 목록은 같은 작업을 돌려주며 성공한 다시 짜기는 새로 받는다")
    void appliesSuccessDeduplicationByType(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, userId);
        var first = service.receive(userId, request);
        GenerationJobFixtures.succeeded(jdbc, first.id(),
                type == JobType.REPLAN_COURSE || type == JobType.PLAN_ROADMAP_COURSE ? request.targetId() : null,
                type == JobType.REPLAN_ROADMAP ? request.targetId() : null,
                type == JobType.MATERIAL ? request.targetId() : null, clock.instant());

        // when
        var second = service.receive(userId, request);

        // then
        if (type == JobType.MATERIAL || type == JobType.PLAN_ROADMAP_COURSE) {
            assertThat(second).isEqualTo(new JobResult(first.id(), JobStatus.SUCCEEDED));
            assertThat(entryCount("CONSUME")).isEqualTo(1);
        } else {
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(entryCount("CONSUME")).isEqualTo(2);
        }
        assertBalanceMatchesLedger(userId);
    }

    @Test
    @DisplayName("작업 받기와 다시 시도 로그는 jobId를 담고 입력과 잔류 MDC를 남기지 않는다")
    void logsOnlySafeTransitionsAndClearsJobMdc(CapturedOutput output) {
        // given
        long userId = user(100);
        String marker = "LEAKCHECK-job-input";
        var request = new JobRequest(JobType.NEW_COURSE, null,
                new LearningInput(marker, Level.BEGINNER, null, Purpose.WORK, null, marker), null);
        var result = service.receive(userId, request);
        fail(result.id(), JobErrorType.INVALID_OUTPUT);
        ledger.restoreIfOwed(userId, result.id());
        MDC.put(RequestLogFilter.REQUEST_ID, "test-job-request");
        MDC.put(RequestLogFilter.USER_ID, Long.toString(userId));

        // when
        try {
            service.retry(userId, result.id());

            // then
            var received = output.getAll().lines().filter(line -> line.contains("job received type=NEW_COURSE")).toList();
            var retried = output.getAll().lines().filter(line -> line.contains("job retried")).toList();
            assertThat(received).singleElement().asString().contains(" INFO ", "jobId=" + result.id());
            assertThat(retried).singleElement().asString().contains(" INFO ", "jobId=" + result.id(),
                    "requestId=test-job-request", "userId=" + userId);
            assertThat(output.getAll()).doesNotContain(marker);
            assertThat(MDC.get(RequestLogFilter.JOB_ID)).isNull();
            assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isEqualTo("test-job-request");
            assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo(Long.toString(userId));
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("기존 작업 반환과 거절과 버리기는 작업 INFO 로그를 더하지 않는다")
    void skipsLogsForUnchangedAndRejectedOperations(CapturedOutput output) {
        // given
        long userId = user(100);
        var request = request(JobType.REPLAN_COURSE, userId);
        var result = service.receive(userId, new JobRequest(request.type(), request.targetId(), null, "LEAKCHECK-feedback"));
        long before = jobLogCount(output);

        // when
        service.receive(userId, request);
        service.retry(userId, result.id());
        assertError(() -> service.discard(userId, result.id()), ErrorCode.JOB_DISCARD_NOT_ALLOWED);
        fail(result.id(), JobErrorType.INPUT_TOO_LARGE);
        assertError(() -> service.retry(userId, result.id()), ErrorCode.JOB_RETRY_NOT_ALLOWED);
        service.discard(userId, result.id());

        // then
        assertThat(jobLogCount(output)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("LEAKCHECK-feedback");
        assertThat(MDC.get(RequestLogFilter.JOB_ID)).isNull();
    }

    private long user(int amount) {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = users.save(UserFixtures.approved("user" + userCount() + "@example.com", amount, clock.instant().plusSeconds(30L * 86400)));
            entries.save(CreditEntry.refill(user, amount));
            return user.getId();
        });
    }

    private JobRequest request(JobType type, long owner) {
        Long target = switch (type) {
            case MATERIAL -> lessons.save(LessonFixtures.lesson(courses.save(CourseFixtures.single(owner)), 1)).getId();
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courses.save(CourseFixtures.single(owner)).getId();
            case REPLAN_ROADMAP -> roadmaps.save(RoadmapFixtures.roadmap(owner)).getId();
            default -> null;
        };
        return new JobRequest(type, target, target == null
                ? new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null) : null, null);
    }

    private int cost(JobType type) {
        return switch (type) {
            case NEW_COURSE -> credits.newCourse();
            case NEW_ROADMAP -> credits.newRoadmap();
            case PLAN_ROADMAP_COURSE -> credits.planRoadmapCourse();
            case REPLAN_COURSE -> credits.replanCourse();
            case REPLAN_ROADMAP -> credits.replanRoadmap();
            case MATERIAL -> credits.material();
        };
    }

    private void fail(long id, JobErrorType error) { GenerationJobFixtures.failed(jdbc, id, error, clock.instant()); }
    private int jobCount() { return jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class); }
    private int userCount() { return jdbc.queryForObject("SELECT count(*) FROM users", Integer.class); }
    private int entryCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE type = ?", Integer.class, type);
    }
    private static long jobLogCount(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("job received") || line.contains("job retried")).count();
    }
    private void assertBalanceMatchesLedger(long userId) {
        assertThat(jdbc.queryForObject("""
                SELECT u.credit_balance = sum(e.amount) FROM users u JOIN credit_entries e ON e.user_id = u.id
                WHERE u.id = ? GROUP BY u.id
                """, Boolean.class, userId)).isTrue();
    }
    private List<Object> concurrently(long userId, Callable<?> first, Callable<?> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2); var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, userId);
                statement.execute();
            }
            var futures = List.of(executor.submit(outcome(first)), executor.submit(outcome(second)));
            int waiting;
            try {
                waiting = awaitLockWaiters();
            } finally {
                connection.commit();
            }
            assertThat(waiting).isEqualTo(2);
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }
    private static Callable<Object> outcome(Callable<?> action) {
        return () -> {
            try { return action.call(); }
            catch (ApiException exception) { return exception; }
        };
    }
    private int awaitLockWaiters() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        int waiting;
        do {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND query LIKE '%users%'
                    """, Integer.class);
            if (waiting >= 2) return waiting;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return waiting;
    }
    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
