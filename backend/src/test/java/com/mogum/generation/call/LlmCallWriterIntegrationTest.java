package com.mogum.generation.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class LlmCallWriterIntegrationTest {
	@Autowired
	private LlmCallWriter llmCallWriter;
	@Autowired
	private LlmCallRepository llmCallRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private GenerationJobRepository generationJobRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TestClock testClock;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;

	@Test
	@DisplayName("트랜잭션 밖에서 시작하면 연결과 호출 정보를 즉시 커밋하고 종료 칸은 비운다")
	void startsAndCommitsWithoutCallerTransaction() throws Exception {
		// given
		long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		long job = generationJobRepository.save(GenerationJobFixtures.newCourse(user, input)).getId();
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

		// when
		long id = llmCallWriter.start(job, user, JobType.NEW_COURSE, LlmCallType.CONTINUATION, "test-model");

		// then
		var actual = llmCallRepository.findById(id).orElseThrow();
		assertThat(actual.getJobId()).isEqualTo(job);
		assertThat(actual.getUserId()).isEqualTo(user);
		assertThat(actual.getJobType()).isEqualTo(JobType.NEW_COURSE);
		assertThat(actual.getCallType()).isEqualTo(LlmCallType.CONTINUATION);
		assertThat(actual.getModel()).isEqualTo("test-model");
		assertThat(actual.getOutcome()).isNull();
		assertThat(actual.getDurationMs()).isNull();
		assertThat(actual.getCreatedAt()).isEqualTo(testClock.instant());
		assertThat(actual.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertVisibleFromOtherConnection(id, false);
	}

	@Test
	@DisplayName("종료는 결과와 사용량을 즉시 커밋하고 수정 시각을 공용 시계로 바꾼다")
	void finishesAndCommitsUsageWithUpdatedAt() throws Exception {
		// given
		long id = llmCallWriter.start(null, null, JobType.NEW_ROADMAP, LlmCallType.CHAT, "test-model");
		var createdAt = testClock.instant();
		testClock.advance(Duration.ofSeconds(12));

		// when
		llmCallWriter.finish(id, LlmCallOutcome.SUCCEEDED, 123, 456, new BigDecimal("0.00123456789"), 789);

		// then
		var actual = llmCallRepository.findById(id).orElseThrow();
		assertThat(actual.getOutcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
		assertThat(actual.getInputTokens()).isEqualTo(123);
		assertThat(actual.getOutputTokens()).isEqualTo(456);
		assertThat(actual.getCost()).isEqualByComparingTo("0.00123456789");
		assertThat(actual.getDurationMs()).isEqualTo(789);
		assertThat(actual.getCreatedAt()).isEqualTo(createdAt);
		assertThat(actual.getUpdatedAt()).isEqualTo(testClock.instant()).isAfter(createdAt);
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertVisibleFromOtherConnection(id, true);
	}

	@ParameterizedTest(name = "없는 작업 {0}, 없는 사용자 {1}")
	@CsvSource({"true,false", "false,true", "true,true"})
	@DisplayName("없는 연결만 비우고 만들기 종류를 남긴다")
	void clearsOnlyMissingLinks(boolean missingJob, boolean missingUser) {
		// given
		long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		long job = generationJobRepository.save(GenerationJobFixtures.newCourse(user, input)).getId();

		// when
		long id = llmCallWriter.start(missingJob ? -1L : job, missingUser ? -2L : user,
			JobType.NEW_COURSE, LlmCallType.CHAT, "test-model");

		// then
		var actual = llmCallRepository.findById(id).orElseThrow();
		assertThat(actual.getJobId()).isEqualTo(missingJob ? null : job);
		assertThat(actual.getUserId()).isEqualTo(missingUser ? null : user);
		assertThat(actual.getJobType()).isEqualTo(JobType.NEW_COURSE);
	}

	@Test
	@DisplayName("시작 뒤 작업과 사람이 지워져도 종료 결과를 남기고 모르는 사용량은 비운다")
	void finishesDisconnectedCallWithoutUsage() {
		// given
		long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		long job = generationJobRepository.save(GenerationJobFixtures.newCourse(user, input)).getId();
		long id = llmCallWriter.start(job, user, JobType.NEW_COURSE, LlmCallType.CHAT, "test-model");
		jdbcTemplate.update("DELETE FROM generation_jobs WHERE id = ?", job);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", user);

		// when
		llmCallWriter.finish(id, LlmCallOutcome.TIMEOUT, null, null, null, 1000);

		// then
		var actual = llmCallRepository.findById(id).orElseThrow();
		assertThat(actual.getJobId()).isNull();
		assertThat(actual.getUserId()).isNull();
		assertThat(actual.getJobType()).isEqualTo(JobType.NEW_COURSE);
		assertThat(actual.getOutcome()).isEqualTo(LlmCallOutcome.TIMEOUT);
		assertThat(actual.getDurationMs()).isEqualTo(1000);
		assertThat(actual.getInputTokens()).isNull();
		assertThat(actual.getOutputTokens()).isNull();
		assertThat(actual.getCost()).isNull();
	}

	@Test
	@DisplayName("호출자 트랜잭션이 롤백되어도 시작과 종료는 각자 커밋된다")
	void commitsIndependentlyOfCallerRollback() {
		// given
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);

		// when
		long id = transactionTemplate.execute(status -> {
			long call = llmCallWriter.start(null, null, JobType.MATERIAL, LlmCallType.EMBEDDING, "test-model");
			llmCallWriter.finish(call, LlmCallOutcome.SUCCEEDED, 10, null, null, 20);
			status.setRollbackOnly();
			return call;
		});

		// then
		assertThat(llmCallRepository.findById(id).orElseThrow().getOutcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
	}

	private void assertVisibleFromOtherConnection(long id, boolean finished) throws Exception {
		try (var executor = Executors.newSingleThreadExecutor()) {
			var result = executor.submit(() -> jdbcTemplate.queryForObject(
				"SELECT (outcome IS NOT NULL) = ? FROM llm_calls WHERE id = ?", Boolean.class, finished, id));
			assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
		}
	}
}
