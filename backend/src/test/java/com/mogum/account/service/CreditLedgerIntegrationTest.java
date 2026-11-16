package com.mogum.account.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class CreditLedgerIntegrationTest {
    @Autowired CreditLedger creditLedger;
    @Autowired UserRepository userRepository;
    @Autowired CreditEntryRepository creditEntryRepository;
    @Autowired GenerationJobRepository generationJobRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager platformTransactionManager;
    @Autowired TestClock testClock;

    @Test
    @DisplayName("동시에 두 번 쓰면 users 행에서 기다린 뒤 한 번만 쓰고 잔액과 기록의 합이 같다")
    void serializesConcurrentConsumes() throws Exception {
        // given
        var data = prepare();

        // when
        var results = concurrently(data.userId(), () -> creditLedger.consume(data.userId(), data.firstJobId(), 10),
                () -> creditLedger.consume(data.userId(), data.secondJobId(), 10));

        // then
        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList()).singleElement()
                .satisfies(error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CREDIT_INSUFFICIENT));
        assertBalance(data.userId(), 0);
        assertThat(entryCount("CONSUME")).isEqualTo(1);
        assertSameClock();
    }

    @ParameterizedTest(name = "동시 호출 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("복구를 두 번 불러도 같은 시각의 기록에서 한 번만 돌려준다")
    void restoresOnlyOnce(boolean concurrent) throws Exception {
        // given
        var data = prepare();
        creditLedger.consume(data.userId(), data.firstJobId(), 7);

        // when
        if (concurrent) {
            assertThat(concurrently(data.userId(), () -> creditLedger.restoreIfOwed(data.userId(), data.firstJobId()),
                    () -> creditLedger.restoreIfOwed(data.userId(), data.firstJobId()))).containsOnlyNulls();
        } else {
            creditLedger.restoreIfOwed(data.userId(), data.firstJobId());
            creditLedger.restoreIfOwed(data.userId(), data.firstJobId());
        }

        // then
        assertBalance(data.userId(), 10);
        assertThat(entryCount("RESTORE")).isEqualTo(1);
        assertSameClock();
    }

    @Test
    @DisplayName("씀 뒤 같은 시각에 채우면 지난 주기의 씀은 복구하지 않는다")
    void skipsConsumeBeforeRefillAtSameInstant() {
        // given
        var data = prepare();
        creditLedger.consume(data.userId(), data.firstJobId(), 7);
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
            creditLedger.lock(data.userId());
            // 채우기 기능은 후속 TASK다. 여기서는 DB에 계약의 주기 경계를 준비한다.
            jdbcTemplate.update("UPDATE users SET credit_balance = 10, updated_at = ? WHERE id = ?",
                    Timestamp.from(testClock.instant()), data.userId());
            creditEntryRepository.save(CreditEntry.refill(userRepository.findById(data.userId()).orElseThrow(), 7));
        });

        // when
        creditLedger.restoreIfOwed(data.userId(), data.firstJobId());

        // then
        assertBalance(data.userId(), 10);
        assertThat(entryCount("RESTORE")).isZero();
        assertSameClock();
    }

    @Test
    @DisplayName("잠금 메서드는 호출한 트랜잭션이 끝날 때까지 다른 씀을 users 행에서 기다리게 한다")
    void retainsLockUntilCallerTransactionEnds() throws Exception {
        // given
        var data = prepare();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = new AtomicReference<Future<?>>();

            // when
            new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
                creditLedger.lock(data.userId());
                future.set(executor.submit(() -> creditLedger.consume(data.userId(), data.firstJobId(), 4)));
                assertThat(awaitLockWaiters(1)).isEqualTo(1);
                assertThat(future.get().isDone()).isFalse();
            });

            // then
            future.get().get(10, TimeUnit.SECONDS);
            assertBalance(data.userId(), 6);
        }
    }

    @ParameterizedTest(name = "복구 동작 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("씀과 복구는 호출한 트랜잭션이 롤백되면 잔액과 기록도 함께 롤백된다")
    void participatesInCallerRollback(boolean restore) {
        // given
        var data = prepare();
        if (restore) creditLedger.consume(data.userId(), data.firstJobId(), 7);
        int previousCount = jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries", Integer.class);

        // when
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
            if (restore) creditLedger.restoreIfOwed(data.userId(), data.firstJobId());
            else creditLedger.consume(data.userId(), data.firstJobId(), 7);
            status.setRollbackOnly();
        });

        // then
        assertBalance(data.userId(), restore ? 3 : 10);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isEqualTo(previousCount);
    }

    @Test
    @DisplayName("기록 저장의 FK 오류가 나면 앞서 바꾼 잔액도 롤백된다")
    void rollsBackBalanceWhenEntryCannotBeSaved() {
        // given
        var data = prepare();

        // when & then
        assertThatThrownBy(() -> creditLedger.consume(data.userId(), -1L, 7)).isInstanceOf(RuntimeException.class);
        assertBalance(data.userId(), 10);
        assertThat(entryCount("CONSUME")).isZero();
    }

    @Test
    @DisplayName("잠금 뒤 씀은 JPA Auditing으로 잔액의 수정 시각도 갱신한다")
    void auditsBalanceUpdate() {
        // given
        var data = prepare();
        jdbcTemplate.update("UPDATE users SET updated_at = ? WHERE id = ?",
                Timestamp.from(testClock.instant().minusSeconds(60)), data.userId());

        // when
        creditLedger.consume(data.userId(), data.firstJobId(), 4);

        // then
        assertThat(userRepository.findById(data.userId()).orElseThrow().getUpdatedAt()).isEqualTo(testClock.instant());
        assertBalance(data.userId(), 6);
        assertSameClock();
    }

    private Data prepare() {
        return new TransactionTemplate(platformTransactionManager).execute(status -> {
            var user = userRepository.save(UserFixtures.approved("me@example.com", 10, testClock.instant().plusSeconds(30L * 86400)));
            creditEntryRepository.save(CreditEntry.refill(user, 10));
            var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
            var first = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input));
            var second = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input));
            return new Data(user.getId(), first.getId(), second.getId());
        });
    }

    private List<ApiException> concurrently(long userId, Runnable first, Runnable second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2); var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, userId);
                statement.execute();
            }
            var futures = List.of(executor.submit(outcome(first)), executor.submit(outcome(second)));
            int waiting = awaitLockWaiters(2);
            connection.commit();
            assertThat(waiting).isEqualTo(2);
            // 성공은 null이므로 null을 허용하는 목록에 담는다.
            return Arrays.asList(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private static Callable<ApiException> outcome(Runnable action) {
        return () -> {
            try {
                action.run();
                return null;
            } catch (ApiException exception) {
                return exception;
            }
        };
    }

    private int awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        int waiting;
        do {
            // 호출자 트랜잭션 안에서도 다른 세션의 현재 대기 상태를 다시 읽는다.
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND query LIKE '%users%'
                    """, Integer.class);
            if (waiting >= expected) return waiting;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting DB locks");
            }
        } while (System.nanoTime() < deadline);
        return waiting;
    }

    private void assertBalance(long userId, int expected) {
        assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT u.credit_balance = sum(e.amount) FROM users u JOIN credit_entries e ON e.user_id = u.id
                WHERE u.id = ? GROUP BY u.id
                """, Boolean.class, userId)).isTrue();
    }

    private int entryCount(String type) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = ?", Integer.class, type);
    }

    private void assertSameClock() {
        assertThat(jdbcTemplate.queryForList("SELECT created_at FROM credit_entries", Timestamp.class))
                .allSatisfy(created -> assertThat(created.toInstant()).isEqualTo(testClock.instant()));
    }

    private record Data(long userId, long firstJobId, long secondJobId) { }
}
