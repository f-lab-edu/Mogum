package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.job.domain.JobType;

class ReplanCourseIntakeHandlerTest {
	private final FakeCourseRepository courseRepository = new FakeCourseRepository();
	private final ReplanCourseIntakeHandler replanCourseIntakeHandler = new ReplanCourseIntakeHandler(courseRepository);

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("다시 짜기는 검토 중에만 받고 상태를 바꾸지 않는다")
	void acceptsOnlyReviewing(CourseStatus status) {
		// given
		var target = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(1L), status));
		var request = new JobRequest(JobType.REPLAN_COURSE, target.getId(), null, "피드백");

		// when & then
		if (status == CourseStatus.REVIEWING) {
			replanCourseIntakeHandler.check(1L, request);
			assertThat(replanCourseIntakeHandler.type()).isEqualTo(JobType.REPLAN_COURSE);
		} else {
			assertThatThrownBy(() -> replanCourseIntakeHandler.check(1L, request))
				.isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REPLAN_NOT_ALLOWED));
		}
		assertThat(target.getStatus()).isEqualTo(status);
	}

	@Test
	@DisplayName("없는 대상은 없는 것으로 답한다")
	void rejectsMissingTarget() {
		// given
		var request = new JobRequest(JobType.REPLAN_COURSE, 999L, null, null);

		// when & then
		assertThatThrownBy(() -> replanCourseIntakeHandler.check(1L, request))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
