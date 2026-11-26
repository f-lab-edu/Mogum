package com.mogum.course.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.service.CreditLedger;
import com.mogum.card.service.CardCommandService;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.JpaCourseDeletionRepository;
import com.mogum.course.repository.LessonRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class CourseDeletionService {
	private final CourseRepository courseRepository;
	private final JpaCourseDeletionRepository jpaCourseDeletionRepository;
	private final LessonRepository lessonRepository;
	private final CreditLedger creditLedger;
	private final CardCommandService cardCommandService;
	private final Clock clock;

	public void delete(long userId, long courseId) {
		if (ownedCourse(userId, courseId).getRoadmap() != null) {
			throw new ApiException(ErrorCode.COURSE_DELETE_NOT_ALLOWED);
		}
		deleteContent(userId, courseId);
	}

	/** 단일 코스의 작업을 처리하고 공통 내용 지우기를 부른다. */
	public void deleteContent(long userId, long courseId) {
		ownedCourse(userId, courseId);
		// 워커와 같은 순서: 작업 행 → 사용자 행. 카드와 강의는 기다린 뒤 다시 읽는다.
		var jobs = jpaCourseDeletionRepository.lockCourseJobs(courseId);
		creditLedger.lock(userId);
		for (var job : jobs) {
			if (job.unfinished()) {
				creditLedger.restoreIfOwed(userId, job.id());
			}
		}
		jpaCourseDeletionRepository.deleteJobs(jobs.stream().map(job -> job.id()).toList());
		deleteContentAfterJobs(userId, courseId);
	}

	/** 호출자가 대상의 모든 작업 잠금·복구·삭제와 사용자 잠금을 마친 뒤 같은 트랜잭션에서 쓴다. */
	public void deleteContentAfterJobs(long userId, long courseId) {
		ownedCourse(userId, courseId);
		var lessonIds = lessonRepository.findByCourseIdOrderByPositionAsc(courseId).stream()
			.map(lesson -> lesson.getId()).toList();
		cardCommandService.hideLessonCards(lessonIds, clock.instant());
		jpaCourseDeletionRepository.deleteMaterials(courseId);
		jpaCourseDeletionRepository.deleteLessons(courseId);
		jpaCourseDeletionRepository.deleteCourse(courseId);
	}

	private Course ownedCourse(long userId, long courseId) {
		return courseRepository.findById(courseId).filter(course -> course.getUserId() == userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}
}
