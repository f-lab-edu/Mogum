package com.mogum.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JpaCourseJobRepository implements CourseJobRepository {
	private final EntityManager entityManager;

	@Override
	@SuppressWarnings("unchecked")
	public List<PlanningJob> findUnfinishedPlanningJobs(long userId, long roadmapId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = entityManager.createNativeQuery("""
			SELECT j.course_id, j.id, j.status = 'FAILED'
			FROM generation_jobs j JOIN courses c ON c.id = j.course_id
			WHERE j.user_id = :userId AND c.user_id = :userId AND c.roadmap_id = :roadmapId
			  AND j.type = 'PLAN_ROADMAP_COURSE' AND j.status IN ('QUEUED', 'RUNNING', 'FAILED')
			ORDER BY j.id DESC
			""", Object[].class)
			// @checkstyle:on
			.setParameter("userId", userId)
			.setParameter("roadmapId", roadmapId)
			.getResultList();
		return rows.stream().map(row -> new PlanningJob(
			((Number)row[0]).longValue(), ((Number)row[1]).longValue(), (Boolean)row[2])).toList();
	}

	@Override
	public boolean hasUnfinishedReplan(long userId, long courseId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		return (Boolean)entityManager.createNativeQuery("""
			SELECT EXISTS (SELECT 1 FROM generation_jobs
			WHERE user_id = :userId AND course_id = :courseId AND type = 'REPLAN_COURSE'
			  AND status IN ('QUEUED', 'RUNNING', 'FAILED'))
			""", Boolean.class)
			// @checkstyle:on
			.setParameter("userId", userId)
			.setParameter("courseId", courseId)
			.getSingleResult();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Optional<MaterialJob> findMaterialJob(long userId, long lessonId) {
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		List<Object[]> rows = entityManager.createNativeQuery("""
			SELECT j.id, j.status FROM generation_jobs j
			JOIN lessons l ON l.id = j.lesson_id JOIN courses c ON c.id = l.course_id
			WHERE j.user_id = :userId AND c.user_id = :userId
			  AND j.lesson_id = :lessonId AND j.type = 'MATERIAL'
			""", Object[].class)
			// @checkstyle:on
			.setParameter("userId", userId).setParameter("lessonId", lessonId).getResultList();
		return rows.stream().findFirst().map(row -> new MaterialJob(((Number)row[0]).longValue(), (String)row[1]));
	}
}
