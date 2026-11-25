package com.mogum.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.common.time.SeoulClock;
import com.mogum.config.CreditProperties;
import com.mogum.config.CreditRefillConfiguration;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CreditRefillServiceIntegrationTest {
	@Autowired
	CreditRefillService creditRefillService;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	CreditProperties creditProperties;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	DataSource dataSource;
	@Autowired
	TestClock testClock;
	@Autowired
	ApplicationContext applicationContext;

	private static final Instant DUE = Instant.parse("2026-11-07T19:00:00Z");

	@ParameterizedTest(name = "이전 잔액 {0}, 바뀐 금액 {1}")
	@CsvSource({"7,18", "25,0", "40,-15"})
	@DisplayName("때가 된 승인 사용자는 0과 음수를 포함한 차액을 기록하고 서울 04시를 7일 옮긴다")
	void refillsDueUserAndPreservesLedger(int balance, int delta, CapturedOutput output) {
		// given
		var user = prepare("me@example.com", balance, DUE);
		testClock.advance(Duration.ofSeconds(1));

		// when
		creditRefillService.refillDue();

		// then
		assertRefilled(user, DUE.atZone(SeoulClock.ZONE).plusDays(7).toInstant(), delta);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getUpdatedAt())
			.isEqualTo(testClock.instant()).isAfter(user.getUpdatedAt());
		assertThat(output.getAll().lines().filter(line -> line.contains("CreditRefillService  :"))).isEmpty();
	}

	@Test
	@DisplayName("22일 밀려도 채움은 한 번이고 다음 때는 지금 뒤의 첫 7일 주기다")
	void catchesUpWithOneRefill() {
		// given
		var user = prepare("me@example.com", 8, DUE);
		testClock.set(DUE.plus(Duration.ofDays(22)));

		// when
		creditRefillService.refillDue();

		// then
		assertRefilled(user, DUE.atZone(SeoulClock.ZONE).plusDays(28).toInstant(), 17);
	}

	@Test
	@DisplayName("아직 때가 안 된 사람과 신청 및 거절 상태는 바꾸지 않는다")
	void skipsFutureAndUnapprovedUsers() {
		// given
		var future = prepare("future@example.com", 8, DUE.plus(Duration.ofDays(7)));
		var applied = userRepository.save(UserFixtures.user("applied@example.com"));
		var rejected = userRepository.save(UserFixtures.withStatus(UserFixtures.user("rejected@example.com"),
			UserStatus.REJECTED));

		// when
		creditRefillService.refillDue();

		// then
		for (var user : new User[] {future, applied, rejected}) {
			var actual = userRepository.findById(user.getId()).orElseThrow();
			assertThat(actual.getCreditBalance()).isEqualTo(user.getCreditBalance());
			assertThat(actual.getCreditRefillAt()).isEqualTo(user.getCreditRefillAt());
			assertThat(actual.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
		}
		assertThat(entryCount()).isEqualTo(1);
	}

	@ParameterizedTest(name = "경계에서 {0}초 전")
	@ValueSource(ints = {60, 0})
	@DisplayName("서울 03시 59분에는 그대로이고 04시에 채운다")
	void observesSeoulBoundary(int secondsBefore) {
		// given
		var user = prepare("me@example.com", 8, DUE);
		testClock.set(DUE.minusSeconds(secondsBefore));

		// when
		creditRefillService.refillDue();

		// then
		assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance())
			.isEqualTo(secondsBefore == 0 ? 25 : 8);
		assertThat(entryCount()).isEqualTo(secondsBefore == 0 ? 2 : 1);
	}

	@Test
	@DisplayName("같은 실행을 두 번 불러도 두 번째는 시각과 기록을 바꾸지 않는다")
	void doesNothingOnSecondCall() {
		// given
		var user = prepare("me@example.com", 8, DUE);
		creditRefillService.refillDue();
		var previous = userRepository.findById(user.getId()).orElseThrow();
		testClock.advance(Duration.ofSeconds(1));

		// when
		creditRefillService.refillDue();

		// then
		assertRefilled(user, DUE.plus(Duration.ofDays(7)), 17);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getUpdatedAt())
			.isEqualTo(previous.getUpdatedAt());
	}

	@Test
	@DisplayName("두 실행이 같은 사용자를 골라도 DB 행 잠금 뒤 확인하여 한 번만 채운다")
	void serializesConcurrentRefills() throws Exception {
		// given
		var user = prepare("me@example.com", 8, DUE);
		try (var executor = Executors.newFixedThreadPool(2); var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
				statement.setLong(1, user.getId());
				statement.execute();
			}
			var first = executor.submit(creditRefillService::refillDue);
			var second = executor.submit(creditRefillService::refillDue);
			try {
				assertThat(awaitLockWaiters(2)).isEqualTo(2);
			} finally {
				connection.rollback();
			}

			// when
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);

			// then
			assertRefilled(user, DUE.plus(Duration.ofDays(7)), 17);
		}
	}

	@ParameterizedTest(name = "잠근 사이 변경 {0}")
	@ValueSource(strings = {"future", "rejected", "deleted"})
	@DisplayName("대상을 고른 뒤 잠그기 전에 때나 상태가 바뀌거나 지워지면 건너뛴다")
	void rechecksLockedUser(String change) throws Exception {
		// given
		var user = prepare("me@example.com", 8, DUE);
		try (var executor = Executors.newSingleThreadExecutor(); var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
				statement.setLong(1, user.getId());
				statement.execute();
			}
			var future = executor.submit(creditRefillService::refillDue);
			try {
				assertThat(awaitLockWaiters(1)).isEqualTo(1);
				var sql = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
					connection, true));
				if (change.equals("future")) {
					sql.update("UPDATE users SET credit_refill_at = ?, updated_at = ? WHERE id = ?",
						Timestamp.from(DUE.plus(Duration.ofDays(7))), Timestamp.from(testClock.instant()),
						user.getId());
				} else if (change.equals("rejected")) {
					sql.update("UPDATE users SET status = 'REJECTED', approved_at = NULL, credit_refill_at = NULL, "
						+ "updated_at = ? WHERE id = ?", Timestamp.from(testClock.instant()), user.getId());
				} else {
					sql.update("DELETE FROM credit_entries WHERE user_id = ?", user.getId());
					sql.update("DELETE FROM users WHERE id = ?", user.getId());
				}
				connection.commit();
			} finally {
				connection.rollback();
			}

			// when
			future.get(10, TimeUnit.SECONDS);

			// then
			assertThat(entryCount()).isEqualTo(change.equals("deleted") ? 0 : 1);
			userRepository.findById(user.getId())
				.ifPresent(actual -> assertThat(actual.getCreditBalance()).isEqualTo(8));
		}
	}

	@ParameterizedTest(name = "채움 전 초기 잔액 {0}")
	@ValueSource(ints = {25, 32, 40})
	@DisplayName("0과 음수 채움을 포함하여 채움 전에 쓴 실패 작업은 채움 뒤 복구하지 않는다")
	void skipsRestorationOfPreviousCycle(int initialBalance) {
		// given
		var user = prepare("me@example.com", initialBalance, DUE);
		var job = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(),
			new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null)));
		creditLedger.consume(user.getId(), job.getId(), 7);
		creditRefillService.refillDue();
		GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.PROVIDER_UNAVAILABLE,
			testClock.instant());

		// when
		creditLedger.restoreIfOwed(user.getId(), job.getId());

		// then
		assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(25);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
			Integer.class)).isZero();
		assertBalanceMatchesEntries(user.getId());
	}

	@Test
	@DisplayName("공용 컨텍스트는 자동 채움을 끄므로 때가 지난 사용자도 그대로 남는다")
	void disablesAutomaticRefillInSharedContext() {
		// given
		var user = prepare("me@example.com", 8, DUE);

		// when
		var triggers = applicationContext.getBeansOfType(CreditRefillConfiguration.class);

		// then
		assertThat(applicationContext.getEnvironment().getProperty("app.credit.refill-enabled", Boolean.class))
			.isFalse();
		assertThat(triggers).isEmpty();
		assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(8);
		assertThat(entryCount()).isEqualTo(1);
	}

	@ParameterizedTest(name = "실패한 사람 {0}명")
	@ValueSource(ints = {1, 2})
	@DisplayName("사람마다 저장 실패를 롤백하고 다음 사람은 채우며 스택은 첫 실패에만 남긴다")
	void rollsBackFailuresAndContinuesWithSafeLogs(int failures, CapturedOutput output) {
		// given
		var first = prepare("first@example.com", 8, DUE);
		var second = failures == 2 ? prepare("second@example.com", 9, DUE) : null;
		var third = prepare("third@example.com", 10, DUE);
		var entries = new FakeCreditEntryRepository();
		var failure = new IllegalStateException("private-marker first@example.com",
			new IllegalArgumentException("cause-marker"));
		failure.addSuppressed(new IllegalArgumentException("suppressed-marker"));
		entries.beforeSave(entry -> {
			if (!entry.getUser().getId().equals(third.getId())) {
				throw failure;
			}
			creditEntryRepository.save(entry);
		});
		var service = new CreditRefillService(userRepository, entries, platformTransactionManager,
			testClock, creditProperties);

		// when
		service.refillDue();

		// then
		var failedUsers = failures == 2 ? new User[] {first, second} : new User[] {first};
		for (var user : failedUsers) {
			var actual = userRepository.findById(user.getId()).orElseThrow();
			assertThat(actual.getCreditBalance()).isEqualTo(user.getCreditBalance());
			assertThat(actual.getCreditRefillAt()).isEqualTo(DUE);
			assertThat(actual.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
			assertBalanceMatchesEntries(user.getId());
			assertThat(output.getAll()).contains("userId=" + user.getId());
		}
		assertThat(userRepository.findById(third.getId()).orElseThrow().getCreditBalance()).isEqualTo(25);
		assertThat(entryCount()).isEqualTo(failures + 2);
		assertBalanceMatchesEntries(third.getId());
		assertThat(output.getAll()).doesNotContain("private-marker", "first@example.com", "cause-marker",
			"suppressed-marker");
		assertThat(output.getAll().lines().filter(line -> line.contains("credit refill failed"))).hasSize(failures)
			.allSatisfy(line -> assertThat(line).contains("ERROR"));
		assertThat(output.getAll().lines().filter(line -> line.equals(
			"java.lang.RuntimeException: java.lang.IllegalStateException"))).hasSize(1);
		assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
	}

	private User prepare(String email, int balance, Instant refillAt) {
		return new TransactionTemplate(platformTransactionManager).execute(status -> {
			var user = userRepository.save(UserFixtures.approved(email, balance, refillAt));
			creditEntryRepository.save(CreditEntry.refill(user, balance));
			return user;
		});
	}

	private void assertRefilled(User previous, Instant next, int delta) {
		var actual = userRepository.findById(previous.getId()).orElseThrow();
		assertThat(actual.getCreditBalance()).isEqualTo(25);
		assertThat(actual.getCreditRefillAt()).isEqualTo(next);
		assertThat(next.atZone(SeoulClock.ZONE).toLocalTime()).hasHour(4).hasMinute(0);
		assertThat(jdbcTemplate.queryForList("SELECT amount FROM credit_entries WHERE user_id = ? ORDER BY id",
			Integer.class, previous.getId())).containsExactly(previous.getCreditBalance(), delta);
		assertThat(jdbcTemplate.queryForList("SELECT type FROM credit_entries WHERE user_id = ?",
			String.class, previous.getId())).containsOnly("REFILL");
		assertBalanceMatchesEntries(previous.getId());
	}

	private void assertBalanceMatchesEntries(long userId) {
		assertThat(jdbcTemplate.queryForObject("SELECT credit_balance = (SELECT sum(amount) FROM credit_entries "
			+ "WHERE user_id = users.id) FROM users WHERE id = ?", Boolean.class, userId)).isTrue();
	}

	private int entryCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries", Integer.class);
	}

	private int awaitLockWaiters(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
		do {
			jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
			int count = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
				+ "WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%users%'",
				Integer.class);
			if (count >= expected) {
				return count;
			}
			Thread.sleep(10);
		} while (System.nanoTime() < deadline);
		return 0;
	}
}
