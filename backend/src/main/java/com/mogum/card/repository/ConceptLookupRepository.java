package com.mogum.card.repository;

import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/** 사전의 읽기 전용 검색. 쓰기 경로의 findSameConcept과 달리 flush하지 않는다. */
@Repository
@RequiredArgsConstructor
public class ConceptLookupRepository {

	private final EntityManager entityManager;

	public boolean hasConcepts(long userId) {
		return (Boolean)entityManager.createNativeQuery(
			"SELECT EXISTS (SELECT 1 FROM concepts WHERE user_id = :userId)")
			.setFlushMode(FlushModeType.COMMIT)
			.setParameter("userId", userId)
			.getSingleResult();
	}

	@SuppressWarnings("unchecked")
	public List<ConceptSummary> findRelated(long userId, List<float[]> queries, int limit) {
		if (queries.isEmpty() || limit <= 0) {
			return List.of();
		}
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = vectorQuery("""
			SELECT c.id, c.name, c.definition
			FROM concepts c CROSS JOIN (VALUES %s) AS q(position, embedding)
			WHERE c.user_id = :userId
			GROUP BY c.id
			ORDER BY MIN(c.embedding <=> q.embedding), c.id
			LIMIT :limit
			""", queries)
			// @checkstyle:on
			.setParameter("userId", userId)
			.setParameter("limit", limit)
			.getResultList();
		return rows.stream().map(row -> new ConceptSummary(((Number)row[0]).longValue(),
			(String)row[1], (String)row[2])).toList();
	}

	@SuppressWarnings("unchecked")
	public List<Match> findMatching(long userId, List<float[]> definitions, double threshold) {
		if (definitions.isEmpty()) {
			return List.of();
		}
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = vectorQuery("""
			SELECT q.position, nearest.id
			FROM (VALUES %s) AS q(position, embedding)
			CROSS JOIN LATERAL (
			    SELECT c.id FROM concepts c
			    WHERE c.user_id = :userId
			      AND 1 - (c.embedding <=> q.embedding) >= :threshold
			    ORDER BY c.embedding <=> q.embedding, c.id
			    LIMIT 1
			) nearest
			ORDER BY q.position
			""", definitions)
			// @checkstyle:on
			.setParameter("userId", userId)
			.setParameter("threshold", threshold)
			.getResultList();
		return rows.stream().map(row -> new Match(((Number)row[0]).intValue(), ((Number)row[1]).longValue()))
			.toList();
	}

	private Query vectorQuery(String sql, List<float[]> vectors) {
		var values = new StringJoiner(", ");
		for (int index = 0; index < vectors.size(); index++) {
			values.add("(" + index + ", CAST(:vector" + index + " AS vector))");
		}
		var query = entityManager.createNativeQuery(sql.formatted(values.toString()))
			.setFlushMode(FlushModeType.COMMIT);
		for (int index = 0; index < vectors.size(); index++) {
			query.setParameter("vector" + index, Arrays.toString(vectors.get(index)));
		}
		return query;
	}

	public record ConceptSummary(long id, String name, String definition) {
	}

	public record Match(int position, long conceptId) {
	}
}
