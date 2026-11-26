package com.mogum.account.repository;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 다른 도메인의 Java 타입을 참조하지 않고 탈퇴 범위를 자식부터 지운다. */
@Repository
@RequiredArgsConstructor
public class JpaAccountWithdrawalRepository {
	private final EntityManager entityManager;

	public void lockJobs(long userId) {
		entityManager.createNativeQuery(
			"SELECT id FROM generation_jobs WHERE user_id = :userId ORDER BY id FOR UPDATE")
			.setParameter("userId", userId).getResultList();
	}

	/** 호출자가 모든 작업 행과 사용자 행을 순서대로 잠근 같은 트랜잭션에서 부른다. */
	public void deleteOwnedData(long userId) {
		entityManager.flush();
		delete("DELETE FROM generation_jobs WHERE user_id = :userId", userId);
		delete("DELETE FROM reviews WHERE card_id IN (SELECT id FROM cards WHERE user_id = :userId)", userId);
		delete("DELETE FROM card_concepts WHERE card_id IN (SELECT id FROM cards WHERE user_id = :userId)", userId);
		delete("DELETE FROM cards WHERE user_id = :userId", userId);
		delete("DELETE FROM materials WHERE lesson_id IN (SELECT l.id FROM lessons l "
			+ "JOIN courses c ON c.id = l.course_id WHERE c.user_id = :userId)", userId);
		delete("DELETE FROM lessons WHERE course_id IN (SELECT id FROM courses WHERE user_id = :userId)", userId);
		delete("DELETE FROM courses WHERE user_id = :userId", userId);
		delete("DELETE FROM roadmaps WHERE user_id = :userId", userId);
		delete("DELETE FROM concepts WHERE user_id = :userId", userId);
		delete("DELETE FROM credit_entries WHERE user_id = :userId", userId);
		entityManager.createNativeQuery("DELETE FROM spring_session WHERE principal_name = :principal")
			.setParameter("principal", Long.toString(userId)).executeUpdate();
		delete("DELETE FROM users WHERE id = :userId", userId);
	}

	private void delete(String sql, long userId) {
		entityManager.createNativeQuery(sql).setParameter("userId", userId).executeUpdate();
	}
}
