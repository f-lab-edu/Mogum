package com.mogum.course.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.service.LessonCardActivator;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.dto.LessonCompletionResponse;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class LessonCommandService {
	private final LessonAccessService lessonAccessService;
	private final MaterialRepository materialRepository;
	private final LessonRepository lessonRepository;
	private final CourseRepository courseRepository;
	private final LessonCardActivator lessonCardActivator;
	private final Clock clock;

	public LessonCompletionResponse complete(long userId, long lessonId) {
		var lesson = lessonAccessService.lockAndOpen(userId, lessonId);
		var course = lesson.getCourse();
		if (lesson.getCompletedAt() != null) {
			return new LessonCompletionResponse(course.getId(), false);
		}
		if (materialRepository.findByLessonId(lessonId).isEmpty()) {
			throw new ApiException(ErrorCode.LESSON_MATERIAL_REQUIRED);
		}
		var now = clock.instant();
		lesson.complete(now);
		lessonCardActivator.activate(lessonId, now);
		boolean courseCompleted = lessonRepository.findByCourseIdOrderByPositionAsc(course.getId())
			.stream().allMatch(value -> value.getCompletedAt() != null);
		if (courseCompleted) {
			course.complete(now);
			var roadmap = course.getRoadmap();
			if (roadmap != null && courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId())
				.stream().noneMatch(value -> value.getPosition() > course.getPosition())) {
				roadmap.complete();
			}
		}
		return new LessonCompletionResponse(course.getId(), courseCompleted);
	}
}
