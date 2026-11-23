package com.mogum.course.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.course.dto.LessonResponse;
import com.mogum.course.repository.CourseJobRepository;
import com.mogum.course.repository.MaterialRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class LessonReadService {
	private final LessonAccessService lessonAccessService;
	private final MaterialRepository materialRepository;
	private final CourseJobRepository courseJobRepository;
	private final Clock clock;

	public LessonResponse read(long userId, long lessonId) {
		var lesson = lessonAccessService.lockAndOpen(userId, lessonId);
		var course = lesson.getCourse();
		var material = materialRepository.findByLessonId(lessonId)
			.map(value -> new LessonResponse.MaterialBody(value.getBody())).orElse(null);
		// 네이티브 작업 읽기는 엔티티를 바꾸기 전에 한다.
		var job = material == null ? courseJobRepository.findMaterialJob(userId, lessonId)
			.map(value -> new LessonResponse.MaterialJob(value.id(), value.status())).orElse(null) : null;
		course.openLesson(clock.instant());
		return new LessonResponse(lessonId, course.getId(), course.getTitle(), lesson.getPosition(),
			lesson.getTitle(), lesson.getCompletedAt() != null, material, job);
	}
}
