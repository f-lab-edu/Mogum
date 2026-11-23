package com.mogum.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.EmbeddingSettings;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;
import com.mogum.support.IntegrationTestConfiguration.TestEmbeddingProvider;

@IntegrationTest
class IntegrationTestConfigurationEmbeddingIntegrationTest {
	@Autowired
	private TestEmbeddingProvider testEmbeddingProvider;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;

	@Test
	@DisplayName("기본 벡터는 같은 정의에 결정적이며 입력 순서와 트랜잭션 밖 호출을 관찰한다")
	void returnsDeterministicVectors() {
		// given
		testEmbeddingProvider.reset();
		var request = request(1, "같은 정의", "다른 정의", "같은 정의");

		// when
		var result = testEmbeddingProvider.send(request);

		// then
		assertThat(result.vectors().get(0)).isEqualTo(result.vectors().get(2))
			.isNotEqualTo(result.vectors().get(1));
		assertThat(result.vectors().getFirst()).hasSize(3072);
		assertThat(testEmbeddingProvider.requests(1)).containsExactly(request);
		assertThat(testEmbeddingProvider.outsideTransaction(1)).isTrue();
	}

	@Test
	@DisplayName("정의별 벡터와 작업별 실패를 설정하고 reset으로 설정과 관찰을 비운다")
	void customizesAndResets() {
		// given
		testEmbeddingProvider.reset();
		testEmbeddingProvider.vector("정의", List.of(0f, 1f));
		testEmbeddingProvider.reply(1, TestEmbeddingProvider.failure(GenerationOutcome.Failure.PROVIDER_REJECTED));
		var failed = testEmbeddingProvider.send(request(1, "정의"));
		var success = testEmbeddingProvider.send(request(2, "정의"));

		// when
		testEmbeddingProvider.reset();

		// then
		assertThat(failed.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_REJECTED);
		assertThat(success.vectors()).containsExactly(List.of(0f, 1f));
		assertThat(testEmbeddingProvider.requests(1)).isEmpty();
		assertThat(testEmbeddingProvider.outsideTransaction(1)).isFalse();
		var reset = testEmbeddingProvider.send(request(1, "정의"));
		assertThat(reset.outcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
		assertThat(reset.vectors().getFirst()).hasSize(3072);
	}

	@Test
	@DisplayName("트랜잭션 안에서 직접 부른 스텁은 연결 관찰에 실패를 남긴다")
	void observesTransaction() {
		// given
		testEmbeddingProvider.reset();
		var transaction = new TransactionTemplate(platformTransactionManager);

		// when
		transaction.executeWithoutResult(status -> testEmbeddingProvider.send(request(1, "정의")));

		// then
		assertThat(testEmbeddingProvider.outsideTransaction(1)).isFalse();
	}

	private EmbeddingProvider.Request request(long jobId, String... definitions) {
		return new EmbeddingProvider.Request(jobId, JobType.MATERIAL,
			new EmbeddingSettings("text-embedding-3-large", Duration.ofSeconds(20)), List.of(definitions));
	}
}
