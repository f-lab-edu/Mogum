package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.support.RepositoryTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;

@RepositoryTest
class ConceptSimilaritySearchImplTest {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ConceptRepository conceptRepository;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;
	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("이름과 관계없이 기준값 이상인 개념 중 가장 가까운 하나만 고른다")
	void selectsNearestConcept() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		conceptRepository.save(ConceptFixtures.concept(userId, "격리 수준", "동시 실행 분리", new float[] {4, 3}));
		var nearest = conceptRepository.save(ConceptFixtures.concept(userId, "다른 이름", "가장 가까운 정의",
			new float[] {1, 0}));
		// when
		var actual = new TransactionTemplate(platformTransactionManager)
			.execute(status -> conceptRepository.findSameConcept(userId, new float[] {1, 0}, 0.80));
		// then
		assertThat(actual).isPresent();
		assertThat(actual.orElseThrow().getId()).isEqualTo(nearest.getId());
	}

	@Test
	@DisplayName("다른 사용자의 더 가까운 개념은 비교 대상에서 뺀다")
	void excludesOtherUsers() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long otherId = userRepository.save(UserFixtures.user("other@example.com")).getId();
		var own = conceptRepository.save(ConceptFixtures.concept(userId, "내 개념", "내 정의", new float[] {4, 3}));
		conceptRepository.save(ConceptFixtures.concept(otherId, "다른 사람", "같은 벡터", new float[] {1, 0}));
		// when
		var actual = new TransactionTemplate(platformTransactionManager)
			.execute(status -> conceptRepository.findSameConcept(userId, new float[] {1, 0}, 0.80));
		// then
		assertThat(actual.orElseThrow().getId()).isEqualTo(own.getId());
	}

	@ParameterizedTest(name = "기준값 {0}")
	@ValueSource(doubles = {0.80, 0.800001})
	@DisplayName("유사도가 기준값과 같으면 붙이고 조금이라도 작으면 새 개념으로 판정한다")
	void includesExactThreshold(double threshold) {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		conceptRepository.save(ConceptFixtures.concept(userId, "경계", "유사도 4/5", new float[] {4, 3}));
		// when
		var actual = new TransactionTemplate(platformTransactionManager)
			.execute(status -> conceptRepository.findSameConcept(userId, new float[] {1, 0}, threshold));
		// then
		assertThat(actual.isPresent()).isEqualTo(threshold == 0.80);
	}

	@Test
	@DisplayName("거리가 같은 개념이 여럿이면 작은 ID를 고른다")
	void breaksTiesById() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		var first = conceptRepository.save(ConceptFixtures.concept(userId, "먼저", "첫 정의", new float[] {1, 0}));
		conceptRepository.save(ConceptFixtures.concept(userId, "나중", "둘째 정의", new float[] {1, 0}));
		// when
		var actual = new TransactionTemplate(platformTransactionManager)
			.execute(status -> conceptRepository.findSameConcept(userId, new float[] {1, 0}, 0.80));
		// then
		assertThat(actual.orElseThrow().getId()).isEqualTo(first.getId());
	}

	@Test
	@DisplayName("개념이 없는 사전에서는 빈 결과를 돌려준다")
	void returnsEmptyForEmptyDictionary() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		// when
		var actual = new TransactionTemplate(platformTransactionManager)
			.execute(status -> conceptRepository.findSameConcept(userId, new float[] {1, 0}, 0.80));
		// then
		assertThat(actual).isEmpty();
	}

	@Test
	@DisplayName("자동 flush를 꺼도 같은 트랜잭션에서 바꾼 벡터를 네이티브 검색 전에 반영한다")
	void flushesBeforeNativeSearch() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		var saved = conceptRepository.save(ConceptFixtures.concept(userId, "개념", "정의", new float[] {0, 1}));
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.setFlushMode(FlushModeType.COMMIT);
			var concept = conceptRepository.findById(saved.getId()).orElseThrow();
			concept.getEmbedding()[0] = 1;
			concept.getEmbedding()[1] = 0;
			// when
			var actual = conceptRepository.findSameConcept(userId, new float[] {1, 0}, 0.80);
			// then
			assertThat(actual.orElseThrow().getId()).isEqualTo(saved.getId());
		});
	}
}
