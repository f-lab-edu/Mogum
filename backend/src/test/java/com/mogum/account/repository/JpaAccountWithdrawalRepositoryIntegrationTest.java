package com.mogum.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class JpaAccountWithdrawalRepositoryIntegrationTest {
	@Autowired
	JpaAccountWithdrawalRepository jpaAccountWithdrawalRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("탈퇴의 작업 잠금은 다른 사람과 사용자 행을 잡지 않고 자기 작업을 트랜잭션 끝까지 잡는다")
	void locksOnlyOwnedJobsUntilCommit() throws Exception {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		long otherId = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		long jobId = generationJobRepository.save(GenerationJobFixtures.newCourse(userId,
			CourseFixtures.single(userId).getInput())).getId();
		long otherJobId = generationJobRepository.save(GenerationJobFixtures.newCourse(otherId,
			CourseFixtures.single(otherId).getInput())).getId();
		var held = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var lock = executor.submit(() -> new TransactionTemplate(platformTransactionManager)
				.executeWithoutResult(tx -> {
					jpaAccountWithdrawalRepository.lockJobs(userId);
					held.countDown();
					try {
						assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(exception);
					}
				}));
			try {
				assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
				// when
				var unlocked = new TransactionTemplate(platformTransactionManager).execute(tx -> {
					jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", Long.class,
						userId);
					jdbcTemplate.queryForObject("SELECT id FROM generation_jobs WHERE id = ? FOR UPDATE NOWAIT",
						Long.class, otherJobId);
					return jdbcTemplate.queryForList("SELECT id FROM generation_jobs FOR UPDATE SKIP LOCKED",
						Long.class);
				});
				// then
				assertThat(unlocked).containsExactly(otherJobId).doesNotContain(jobId);
			} finally {
				release.countDown();
			}
			lock.get(10, TimeUnit.SECONDS);
		}
	}
}
