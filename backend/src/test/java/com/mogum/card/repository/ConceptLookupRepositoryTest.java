package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Concept;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.support.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class ConceptLookupRepositoryTest {

	@Autowired
	UserRepository userRepository;
	@Autowired
	ConceptRepository conceptRepository;
	@Autowired
	EntityManager entityManager;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "기준값 {0}")
	@ValueSource(doubles = {0.80, 0.800001})
	@DisplayName("읽기 검색도 쓰기 검색처럼 기준값과 같으면 맞추고 바로 아래면 제외한다")
	void includesExactThresholdLikeWriteSearch(double threshold) {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		conceptRepository.save(ConceptFixtures.concept(userId, "경계", "유사도 4/5", vector(4, 3)));
		var lookup = new ConceptLookupRepository(entityManager);
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		var expected = transactionTemplate
			.execute(status -> conceptRepository.findSameConcept(userId, vector(1, 0), threshold).map(c -> c.getId()));
		transactionTemplate.setReadOnly(true);

		// when
		var actual = transactionTemplate
			.execute(status -> lookup.findMatching(userId, List.of(vector(1, 0)), threshold));

		// then
		assertThat(expected.isPresent()).isEqualTo(threshold == 0.80);
		assertThat(actual).isEqualTo(expected.map(id -> List.of(new ConceptLookupRepository.Match(0, id)))
			.orElseGet(List::of));
	}

	@Test
	@DisplayName("가장 가까운 개념과 동점의 작은 ID를 쓰기 검색과 똑같이 고른다")
	void choosesSameNearestConceptAndTieAsWriteSearch() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		conceptRepository.save(ConceptFixtures.concept(userId, "덜 가까움", "정의", vector(4, 3)));
		var first = conceptRepository.save(ConceptFixtures.concept(userId, "먼저", "정의", vector(1, 0)));
		conceptRepository.save(ConceptFixtures.concept(userId, "나중", "정의", vector(1, 0)));
		var lookup = new ConceptLookupRepository(entityManager);
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		var expected = transactionTemplate
			.execute(status -> conceptRepository.findSameConcept(userId, vector(1, 0), 0.72).orElseThrow().getId());
		transactionTemplate.setReadOnly(true);

		// when
		var actual = transactionTemplate
			.execute(status -> lookup.findMatching(userId, List.of(vector(1, 0), vector(1, 0)), 0.72));

		// then
		assertThat(expected).isEqualTo(first.getId());
		assertThat(actual).containsExactly(new ConceptLookupRepository.Match(0, expected),
			new ConceptLookupRepository.Match(1, expected));
	}

	@Test
	@DisplayName("읽기 전용 트랜잭션의 검색은 관리 중인 개념을 flush하지 않는다")
	void doesNotFlushManagedChangesInReadOnlyTransaction() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		var concept = conceptRepository.save(ConceptFixtures.concept(userId, "기존", "정의", vector(0, 1)));
		var lookup = new ConceptLookupRepository(entityManager);
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		transactionTemplate.setReadOnly(true);
		transactionTemplate.executeWithoutResult(status -> {
			var managed = entityManager.find(Concept.class, concept.getId());
			entityManager.unwrap(Session.class).setReadOnly(managed, false);
			managed.getEmbedding()[0] = 1;
			managed.getEmbedding()[1] = 0;

			// when
			var actual = lookup.findMatching(userId, List.of(vector(1, 0)), 0.72);

			// then
			assertThat(actual).isEmpty();
		});
	}

	private static float[] vector(float first, float second) {
		var vector = new float[3072];
		vector[0] = first;
		vector[1] = second;
		return vector;
	}
}
