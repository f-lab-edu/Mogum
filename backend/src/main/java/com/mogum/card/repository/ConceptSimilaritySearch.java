package com.mogum.card.repository;

import java.util.Optional;

import com.mogum.card.domain.Concept;

public interface ConceptSimilaritySearch {
	/** 쓰기 트랜잭션 안에서 호출한다. 검색 전에 flush하며, 기준값 이상인 가장 가까운 개념 하나를 돌려준다. */
	Optional<Concept> findSameConcept(long userId, float[] embedding, double threshold);
}
