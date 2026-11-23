package com.mogum.course.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Lesson;
import com.mogum.course.repository.LessonRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LessonAccessService {
	private final LessonRepository lessonRepository;

	@Transactional(readOnly = true)
	public Lesson open(long userId, long lessonId) {
		var lesson = lessonRepository.findById(lessonId)
			.filter(value -> value.getCourse().getUserId() == userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		checkOpen(lesson);
		return lesson;
	}

	/** 코스와 강의를 읽기 전에 코스부터 잠가 완료 여부를 커밋 뒤의 값으로 판단한다. */
	@Transactional
	public Lesson lockAndOpen(long userId, long lessonId) {
		lessonRepository.findCourseForUpdate(userId, lessonId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return open(userId, lessonId);
	}

	private void checkOpen(Lesson lesson) {
		var course = lesson.getCourse();
		if ((course.getStatus() != CourseStatus.LEARNING && course.getStatus() != CourseStatus.COMPLETED)
			|| lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).stream()
				.anyMatch(
					previous -> previous.getPosition() < lesson.getPosition() && previous.getCompletedAt() == null)) {
			throw new ApiException(ErrorCode.LESSON_NOT_OPEN);
		}
	}
}
