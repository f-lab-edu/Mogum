package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeLessonRepository;

class LessonAccessServiceTest {
	private final FakeCourseRepository courseRepository = new FakeCourseRepository();
	private final FakeLessonRepository lessonRepository = new FakeLessonRepository();
	private final LessonAccessService lessonAccessService = new LessonAccessService(lessonRepository);

	@ParameterizedTest(name = "상태={0}")
	@ValueSource(strings = {"LEARNING", "COMPLETED"})
	@DisplayName("앞 강의가 완료면 학습 중과 다 마친 코스의 강의를 연다")
	void opensAfterPreviousLessons(String status) {
		// given
		var course = courseRepository
			.save(CourseFixtures.withStatus(CourseFixtures.single(1L), CourseStatus.valueOf(status)));
		lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1), Instant.EPOCH));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 2));
		// when
		var result = lessonAccessService.open(1L, lesson.getId());
		// then
		assertThat(result).isSameAs(lesson);
	}

	@ParameterizedTest(name = "경우={0}")
	@ValueSource(strings = {"MISSING", "FOREIGN", "REVIEWING", "PREVIOUS"})
	@DisplayName("열기 조건은 소유권을 먼저 확인하고 코스 상태와 모든 앞 강의를 검사한다")
	void rejectsUnavailableLessons(String scenario) {
		// given
		var course = courseRepository
			.save(CourseFixtures.withStatus(CourseFixtures.single(scenario.equals("FOREIGN") ? 2L : 1L),
				scenario.equals("REVIEWING") ? CourseStatus.REVIEWING : CourseStatus.LEARNING));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 2), Instant.EPOCH));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 3));
		long id = scenario.equals("MISSING") ? 999L : lesson.getId();
		// when & then
		assertThatThrownBy(() -> lessonAccessService.open(1L, id)).isInstanceOfSatisfying(ApiException.class,
			e -> assertThat(e.errorCode()).isEqualTo(scenario.equals("FOREIGN") || scenario.equals("MISSING")
				? ErrorCode.NOT_FOUND : ErrorCode.LESSON_NOT_OPEN));
	}
}
