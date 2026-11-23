package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeLessonRepository;
import com.mogum.course.service.LessonAccessService;
import com.mogum.job.domain.JobType;

class MaterialIntakeHandlerTest {
	@Test
	@DisplayName("강의 자료 받기는 강의 읽기와 같은 열기 조건을 쓴다")
	void sharesLessonOpeningRules() {
		// given
		var courseRepository = new FakeCourseRepository();
		var lessonRepository = new FakeLessonRepository();
		var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(1L), CourseStatus.LEARNING));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 2));
		var handler = new MaterialIntakeHandler(new LessonAccessService(lessonRepository));
		// when & then
		assertThatThrownBy(() -> handler.check(1L, new JobRequest(JobType.MATERIAL, lesson.getId(), null, null)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LESSON_NOT_OPEN));
	}
}
