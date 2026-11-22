package com.mogum.card.repository;

import java.util.Arrays;
import java.util.Optional;

import com.mogum.card.domain.Concept;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ConceptSimilaritySearchImpl implements ConceptSimilaritySearch {
	private final EntityManager entityManager;

	@Override
	@SuppressWarnings("unchecked")
	public Optional<Concept> findSameConcept(long userId, float[] embedding, double threshold) {
		// 같은 저장에서 먼저 넣은 개념도 찾는다. Hibernate의 자동 flush에 기대지 않는다.
		entityManager.flush();
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		return entityManager.createNativeQuery("""
			SELECT c.* FROM concepts c
			WHERE c.user_id = :userId
			  AND 1 - (c.embedding <=> CAST(:embedding AS vector)) >= :threshold
			ORDER BY c.embedding <=> CAST(:embedding AS vector), c.id
			LIMIT 1
			""", Concept.class)
			// @checkstyle:on
			.setParameter("userId", userId)
			.setParameter("embedding", Arrays.toString(embedding))
			.setParameter("threshold", threshold)
			.getResultStream().findFirst();
	}
}
