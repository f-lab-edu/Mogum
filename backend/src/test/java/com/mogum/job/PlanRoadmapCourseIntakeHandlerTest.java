package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeRoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.FakeGenerationJobRepository;

class PlanRoadmapCourseIntakeHandlerTest {
	private final FakeCourseRepository courseRepository = new FakeCourseRepository();
	private final FakeRoadmapRepository roadmapRepository = new FakeRoadmapRepository();
	private final FakeGenerationJobRepository generationJobRepository = new FakeGenerationJobRepository();

	@ParameterizedTest(name = "로드맵={0}")
	@EnumSource(value = RoadmapStatus.class, names = {"REVIEWING", "IN_PROGRESS"})
	@DisplayName("첫 코스는 검토 중 또는 진행 중 로드맵에서 받아 진행 중이 된다")
	void acceptsFirstCourse(RoadmapStatus status) {
		// given
		var roadmap = roadmap(status);
		var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);

		// when
		handler().check(1L, request(course));

		// then
		assertThat(handler().type()).isEqualTo(JobType.PLAN_ROADMAP_COURSE);
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.IN_PROGRESS);
		assertThat(course.getStatus()).isEqualTo(CourseStatus.NOT_PLANNED);
	}

	@ParameterizedTest(name = "앞 코스={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("다음 코스는 바로 앞 코스가 다 마침일 때만 받는다")
	void requiresCompletedPreviousCourse(CourseStatus previousStatus) {
		// given
		var roadmap = roadmap(RoadmapStatus.IN_PROGRESS);
		course(roadmap, 1, previousStatus);
		var next = course(roadmap, 2, CourseStatus.NOT_PLANNED);

		// when & then
		if (previousStatus == CourseStatus.COMPLETED) {
			handler().check(1L, request(next));
			assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.IN_PROGRESS);
		} else {
			assertRejected(next, ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
		}
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(value = JobStatus.class, names = {"QUEUED", "RUNNING", "FAILED"})
	@DisplayName("끝나지 않은 로드맵 다시 짜기는 첫 코스 시작을 막는다")
	void rejectsUnfinishedReplan(JobStatus status) {
		// given
		var roadmap = roadmap(RoadmapStatus.REVIEWING);
		var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
		var replan = GenerationJobFixtures.replanRoadmap(1L, roadmap.getId(), null);
		GenerationJobFixtures.withStatus(replan, status);
		generationJobRepository.save(replan);

		// when & then
		assertRejected(course, ErrorCode.REPLAN_IN_PROGRESS);
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
	}

	@Test
	@DisplayName("성공한 다시 짜기와 다른 로드맵의 다시 짜기는 시작을 막지 않는다")
	void ignoresSucceededAndOtherReplans() {
		// given
		var roadmap = roadmap(RoadmapStatus.REVIEWING);
		var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
		var succeeded = GenerationJobFixtures.replanRoadmap(1L, roadmap.getId(), null);
		GenerationJobFixtures.withStatus(succeeded, JobStatus.SUCCEEDED);
		generationJobRepository.save(succeeded);
		generationJobRepository.save(GenerationJobFixtures.replanRoadmap(1L,
			roadmap(RoadmapStatus.REVIEWING).getId(), null));

		// when
		handler().check(1L, request(course));

		// then
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.IN_PROGRESS);
	}

	@ParameterizedTest(name = "로드맵={0}, 코스={1}, 순서={2}")
	@CsvSource({"COMPLETED,NOT_PLANNED,1", "REVIEWING,NOT_PLANNED,2",
		"IN_PROGRESS,REVIEWING,1", "IN_PROGRESS,LEARNING,1", "IN_PROGRESS,COMPLETED,1"})
	@DisplayName("이미 목록이 있거나 로드맵 상태가 맞지 않으면 만들기를 거절한다")
	void rejectsInvalidState(RoadmapStatus roadmapStatus, CourseStatus courseStatus, int position) {
		// given
		var course = course(roadmap(roadmapStatus), position, courseStatus);

		// when & then
		assertRejected(course, ErrorCode.COURSE_PLAN_NOT_ALLOWED);
	}

	@Test
	@DisplayName("앞선 짜기 전 코스가 있으면 그 뒤 코스를 먼저 만들 수 없다")
	void rejectsSkippingUnplannedCourse() {
		// given
		var roadmap = roadmap(RoadmapStatus.IN_PROGRESS);
		course(roadmap, 1, CourseStatus.NOT_PLANNED);
		course(roadmap, 2, CourseStatus.COMPLETED);
		var third = course(roadmap, 3, CourseStatus.NOT_PLANNED);

		// when & then
		assertRejected(third, ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
	}

	@Test
	@DisplayName("바로 앞 코스가 없으면 다음 코스 만들기를 받지 않는다")
	void rejectsMissingPreviousCourse() {
		// given
		var next = course(roadmap(RoadmapStatus.IN_PROGRESS), 2, CourseStatus.NOT_PLANNED);

		// when & then
		assertRejected(next, ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
	}

	@Test
	@DisplayName("단일 코스와 없는 코스는 없는 것으로 답한다")
	void rejectsMissingAndSingleCourse() {
		// given
		var single = courseRepository.save(CourseFixtures.single(1L));

		// when & then
		assertRejected(single, ErrorCode.NOT_FOUND);
		assertThatThrownBy(() -> handler().check(1L,
			new JobRequest(JobType.PLAN_ROADMAP_COURSE, 999L, null, null)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	private Roadmap roadmap(RoadmapStatus status) {
		return roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(1L), status));
	}

	private Course course(Roadmap roadmap, int position, CourseStatus status) {
		return courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, position, "계획", "설명", List.of()), status));
	}

	private PlanRoadmapCourseIntakeHandler handler() {
		return new PlanRoadmapCourseIntakeHandler(courseRepository, generationJobRepository);
	}

	private JobRequest request(Course course) {
		return new JobRequest(JobType.PLAN_ROADMAP_COURSE, course.getId(), null, null);
	}

	private void assertRejected(Course course, ErrorCode code) {
		assertThatThrownBy(() -> handler().check(1L, request(course)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
	}
}
