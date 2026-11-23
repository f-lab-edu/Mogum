package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;
import com.mogum.support.IntegrationTest;

import jakarta.persistence.EntityManagerFactory;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class EmbeddingCallIntegrationTest {
	@Autowired
	private EmbeddingCall embeddingCall;
	@Autowired
	private LlmCallWriter llmCallWriter;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;
	@Autowired
	private EntityManagerFactory entityManagerFactory;
	@Autowired
	private DataSource dataSource;

	private final JobSnapshot jobSnapshot = new JobSnapshot(1, 1, JobType.MATERIAL, 1L, null, null, 1);
	private final EmbeddingSettings embeddingSettings = new EmbeddingSettings("text-embedding-3-large",
		Duration.ofSeconds(20));

	@Test
	@DisplayName("호출 전 기록을 커밋하고 연결을 반환한 뒤 한 번 보내 입력 비용만 기록한다")
	void recordsCommittedCallOutsideTransaction(CapturedOutput output) {
		// given
		EmbeddingProvider provider = request -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
			assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM llm_calls", String.class)).isNull();
			try {
				assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class)
					.getHikariPoolMXBean().getActiveConnections()).isZero();
			} catch (java.sql.SQLException exception) {
				throw new AssertionError(exception);
			}
			return new EmbeddingProvider.Reply(List.of(List.of(1f, 2f), List.of(3f, 4f)),
				LlmCallOutcome.SUCCEEDED, null, 100);
		};

		// when
		var result = call(provider);

		// then
		assertThat(result).isEqualTo(new EmbeddingResult.Success(List.of(List.of(1f, 2f), List.of(3f, 4f))));
		var row = row("SUCCEEDED");
		assertThat(row.get("input_tokens")).isEqualTo(100);
		assertThat((BigDecimal)row.get("cost")).isEqualByComparingTo("0.000013");
		assertThat(row.toString()).doesNotContain("DEFINITION_PRIVATE_MARKER");
		assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidVectors")
	@DisplayName("벡터 수와 차원이 어긋나면 사용량과 비용을 남기고 형식 실패로 돌려준다")
	void rejectsInvalidVectors(List<List<Float>> vectors) {
		// given
		EmbeddingProvider provider = request -> new EmbeddingProvider.Reply(vectors,
			LlmCallOutcome.SUCCEEDED, null, 100);

		// when
		var result = call(provider);

		// then
		assertThat(result).isEqualTo(new EmbeddingResult.Failed(GenerationOutcome.Failure.INVALID_OUTPUT));
		assertThat(row("INVALID_OUTPUT").get("input_tokens")).isEqualTo(100);
	}

	static Stream<List<List<Float>>> invalidVectors() {
		return Stream.of(List.of(), List.of(List.of(1f)), List.of(List.of(), List.of()),
			List.of(List.of(1f), List.of(1f, 2f)));
	}

	@Test
	@DisplayName("예상한 실패는 한 번만 부르고 확인한 사용량을 잃지 않는다")
	void recordsExpectedFailureOnce() {
		// given
		var count = new java.util.concurrent.atomic.AtomicInteger();
		EmbeddingProvider provider = request -> {
			count.incrementAndGet();
			return new EmbeddingProvider.Reply(null, LlmCallOutcome.RATE_LIMITED,
				GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, 100);
		};

		// when
		var result = call(provider);

		// then
		assertThat(result).isEqualTo(new EmbeddingResult.Failed(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
		assertThat(count).hasValue(1);
		assertThat(row("RATE_LIMITED").get("input_tokens")).isEqualTo(100);
	}

	@Test
	@DisplayName("사용량 누락이면 성공한 호출의 토큰과 비용을 비운다")
	void preservesMissingUsage() {
		// given
		EmbeddingProvider provider = request -> new EmbeddingProvider.Reply(
			List.of(List.of(1f), List.of(2f)), LlmCallOutcome.SUCCEEDED, null, null);

		// when
		var result = call(provider);

		// then
		assertThat(result).isInstanceOf(EmbeddingResult.Success.class);
		var row = row("SUCCEEDED");
		assertThat(row.get("input_tokens")).isNull();
		assertThat(row.get("cost")).isNull();
	}

	@Test
	@DisplayName("트랜잭션을 쥔 호출은 전송과 기록을 시작하지 않는다")
	void refusesCallInsideTransaction() {
		// given
		EmbeddingProvider provider = request -> {
			throw new AssertionError("Must not send");
		};
		var transaction = new TransactionTemplate(platformTransactionManager);

		// when
		var result = transaction.execute(status -> call(provider));

		// then
		assertThat(result).isEqualTo(new EmbeddingResult.Failed(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isZero();
	}

	@Test
	@DisplayName("예상 밖 예외는 조용히 실패로 바꾸지 않고 기록을 마친 뒤 상위 처리기로 던진다")
	void propagatesUnexpectedException(CapturedOutput output) {
		// given
		var bug = new IllegalStateException("DEFINITION_PRIVATE_MARKER");
		EmbeddingProvider provider = request -> {
			throw bug;
		};

		// when & then
		assertThatThrownBy(() -> call(provider)).isSameAs(bug);
		assertThat(row("PROVIDER_ERROR").get("input_tokens")).isNull();
		assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER");
	}

	@Test
	@DisplayName("공용 빈의 임베딩 호출은 주입된 스텁으로 보낸다")
	void wiresStubIntoCall() {
		// when
		var result = embeddingCall.call(jobSnapshot, embeddingSettings, List.of("정의"));

		// then
		assertThat(result).isInstanceOf(EmbeddingResult.Success.class);
		row("SUCCEEDED");
	}

	private EmbeddingResult call(EmbeddingProvider provider) {
		return new EmbeddingCall(provider, llmCallWriter,
			Map.of(embeddingSettings.model(), new TokenPrices(new BigDecimal("0.13"),
				BigDecimal.ZERO, BigDecimal.ZERO)))
			.call(jobSnapshot, embeddingSettings, List.of("DEFINITION_PRIVATE_MARKER", "두 번째 정의"));
	}

	private Map<String, Object> row(String outcome) {
		var rows = jdbcTemplate.queryForList("SELECT * FROM llm_calls");
		assertThat(rows).hasSize(1);
		var row = rows.getFirst();
		assertThat(row.get("call_type")).isEqualTo("EMBEDDING");
		assertThat(row.get("job_type")).isEqualTo("MATERIAL");
		assertThat(row.get("model")).isEqualTo(embeddingSettings.model());
		assertThat(row.get("outcome")).isEqualTo(outcome);
		assertThat(row.get("output_tokens")).isNull();
		assertThat((Integer)row.get("duration_ms")).isNotNegative();
		return row;
	}
}
