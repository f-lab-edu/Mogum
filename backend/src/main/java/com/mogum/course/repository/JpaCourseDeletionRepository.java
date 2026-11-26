package com.mogum.course.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 작업 도메인의 Java 의존 없이 코스의 작업을 잠그고 자식부터 명시적으로 지운다. */
@Repository
@RequiredArgsConstructor
public class JpaCourseDeletionRepository {
	private final EntityManager entityManager;

	@SuppressWarnings("unchecked")
	public List<DeletionJob> lockCourseJobs(long courseId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = entityManager.createNativeQuery("""
			SELECT j.id, j.status <> 'SUCCEEDED'
			FROM generation_jobs j
			WHERE j.course_id = :courseId
			   OR j.lesson_id IN (SELECT id FROM lessons WHERE course_id = :courseId)
			ORDER BY j.id FOR UPDATE OF j
			""", Object[].class)
			// @checkstyle:on
			.setParameter("courseId", courseId).getResultList();
		return rows.stream().map(row -> new DeletionJob(((Number)row[0]).longValue(), (Boolean)row[1])).toList();
	}

	public void deleteJobs(List<Long> jobIds) {
		// 복구 기록의 INSERT와 사용자 잔액 변경을 FK 연결을 끊기 전에 반영한다.
		entityManager.flush();
		if (!jobIds.isEmpty()) {
			entityManager.createNativeQuery("DELETE FROM generation_jobs WHERE id IN (:ids)")
				.setParameter("ids", jobIds).executeUpdate();
		}
	}

	@SuppressWarnings("unchecked")
	public List<DeletionJob> lockRoadmapJobs(long roadmapId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = entityManager.createNativeQuery("""
			SELECT j.id, j.status <> 'SUCCEEDED'
			FROM generation_jobs j
			WHERE j.roadmap_id = :roadmapId
			   OR j.course_id IN (SELECT id FROM courses WHERE roadmap_id = :roadmapId)
			   OR j.lesson_id IN (
			       SELECT l.id FROM lessons l JOIN courses c ON c.id = l.course_id
			       WHERE c.roadmap_id = :roadmapId)
			ORDER BY j.id FOR UPDATE OF j
			""", Object[].class)
			// @checkstyle:on
			.setParameter("roadmapId", roadmapId).getResultList();
		return rows.stream().map(row -> new DeletionJob(((Number)row[0]).longValue(), (Boolean)row[1])).toList();
	}

	public void deleteRoadmap(long roadmapId) {
		entityManager.createNativeQuery("DELETE FROM roadmaps WHERE id = :roadmapId")
			.setParameter("roadmapId", roadmapId).executeUpdate();
	}

	public void deleteMaterials(long courseId) {
		entityManager.createNativeQuery(
			"DELETE FROM materials WHERE lesson_id IN (SELECT id FROM lessons WHERE course_id = :courseId)")
			.setParameter("courseId", courseId).executeUpdate();
	}

	public void deleteLessons(long courseId) {
		entityManager.createNativeQuery("DELETE FROM lessons WHERE course_id = :courseId")
			.setParameter("courseId", courseId).executeUpdate();
	}

	public void deleteCourse(long courseId) {
		entityManager.createNativeQuery("DELETE FROM courses WHERE id = :courseId")
			.setParameter("courseId", courseId).executeUpdate();
	}

	public record DeletionJob(long id, boolean unfinished) {
	}
}
