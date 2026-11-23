package com.mogum.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class JpaCourseJobRepositoryIntegrationTest {
	@Autowired
	CourseJobRepository courseJobRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	TestClock testClock;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "작업 상태={0}")
	@ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
	@DisplayName("네이티브 작업 읽기는 사용자와 대상과 종류를 구분하고 성공한 다시 짜기는 제외한다")
	void readsUnfinishedJobsByOwnerAndTarget(String status) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId()));
		var course = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));
		var planning = generationJobRepository.save(
			GenerationJobFixtures.planRoadmapCourse(user.getId(), course.getId()));
		var single = courseRepository.save(CourseFixtures.single(user.getId()));
		var replan = generationJobRepository.save(
			GenerationJobFixtures.replanCourse(user.getId(), single.getId(), null));
		for (var job : List.of(planning, replan)) {
			if (status.equals("RUNNING")) {
				GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1,
					testClock.instant(), testClock.instant().plusSeconds(360));
			} else if (status.equals("FAILED")) {
				GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT,
					testClock.instant());
			} else if (status.equals("SUCCEEDED")) {
				GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), job.getCourseId(), null, null,
					testClock.instant());
			}
		}

		// when
		var result = new TransactionTemplate(platformTransactionManager).execute(tx -> List.of(
			courseJobRepository.findUnfinishedPlanningJobs(user.getId(), roadmap.getId()),
			courseJobRepository.hasUnfinishedReplan(user.getId(), single.getId()),
			courseJobRepository.hasUnfinishedReplan(user.getId(), course.getId()),
			courseJobRepository.findUnfinishedPlanningJobs(999L, roadmap.getId()),
			courseJobRepository.hasUnfinishedReplan(999L, single.getId())));

		// then
		assertThat(result.get(0)).isEqualTo(status.equals("SUCCEEDED") ? List.of() : List.of(
			new CourseJobRepository.PlanningJob(course.getId(), planning.getId(), status.equals("FAILED"))));
		assertThat(result.get(1)).isEqualTo(!status.equals("SUCCEEDED"));
		assertThat(result.get(2)).isEqualTo(false);
		assertThat(result.get(3)).isEqualTo(List.of());
		assertThat(result.get(4)).isEqualTo(false);
	}

	@ParameterizedTest(name = "작업 상태={0}")
	@ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
	@DisplayName("강의 작업은 주인과 강의 및 종류로 읽고 상태 이외의 시스템 값은 내보내지 않는다")
	void readsMaterialJobByOwnerAndLesson(String status) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var other = lessonRepository.save(LessonFixtures.lesson(course, 2));
		generationJobRepository.save(GenerationJobFixtures.material(user.getId(), other.getId()));
		generationJobRepository.save(GenerationJobFixtures.replanCourse(user.getId(), course.getId(), null));
		var job = generationJobRepository.save(GenerationJobFixtures.material(user.getId(), lesson.getId()));
		if (status.equals("RUNNING")) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
				testClock.instant().plusSeconds(960));
		} else if (status.equals("FAILED")) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		} else if (status.equals("SUCCEEDED")) {
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), null, null, lesson.getId(), testClock.instant());
		}
		// when
		var result = new TransactionTemplate(platformTransactionManager).execute(tx -> List.of(
			courseJobRepository.findMaterialJob(user.getId(), lesson.getId()),
			courseJobRepository.findMaterialJob(999L, lesson.getId()),
			courseJobRepository.findMaterialJob(user.getId(), 999L)));
		// then
		assertThat(result.get(0)).contains(new CourseJobRepository.MaterialJob(job.getId(), status));
		assertThat(result.get(1)).isEmpty();
		assertThat(result.get(2)).isEmpty();
	}

	@Autowired
	LessonRepository lessonRepository;
}
