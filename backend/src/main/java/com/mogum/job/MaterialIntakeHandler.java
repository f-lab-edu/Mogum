package com.mogum.job;

import org.springframework.stereotype.Component;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.repository.LessonRepository;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 주인은 받기 틀이 먼저 확인한다. 자료 유무와 무관하게 강의를 열 수 있는지만 확인한다. */
@Component
@RequiredArgsConstructor
public final class MaterialIntakeHandler implements JobIntakeHandler {
	private final LessonRepository lessonRepository;

	@Override
	public JobType type() {
		return JobType.MATERIAL;
	}

	@Override
	public void check(long userId, JobRequest request) {
		var lesson = lessonRepository.findById(request.targetId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		var course = lesson.getCourse();
		if ((course.getStatus() != CourseStatus.LEARNING && course.getStatus() != CourseStatus.COMPLETED)
			|| lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).stream()
				.anyMatch(
					previous -> previous.getPosition() < lesson.getPosition() && previous.getCompletedAt() == null)) {
			throw new ApiException(ErrorCode.LESSON_NOT_OPEN);
		}
	}
}
