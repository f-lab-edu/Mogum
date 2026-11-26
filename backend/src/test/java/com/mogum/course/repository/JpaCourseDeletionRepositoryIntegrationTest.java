package com.mogum.course.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class JpaCourseDeletionRepositoryIntegrationTest {
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@Autowired
	JpaCourseDeletionRepository jpaCourseDeletionRepository;
	@Autowired
	RoadmapRepository roadmapRepository;

	@Test
	@DisplayName("로드맵과 모든 코스 및 강의의 작업을 상태와 상관없이 함께 잠그고 다른 결과의 작업은 제외한다")
	void locksEveryRoadmapJobBeforeReturning() {
		// given
		long userId = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var first = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "계획", List.of()));
		var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "다음", "계획", List.of()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(second, 1));
		var creation = generationJobRepository.save(GenerationJobFixtures.newRoadmap(userId, roadmap.getInput()));
		GenerationJobFixtures.succeeded(jdbcTemplate, creation.getId(), null, roadmap.getId(), null,
			testClock.instant());
		var replan = generationJobRepository.save(GenerationJobFixtures.replanRoadmap(userId, roadmap.getId(), null));
		var planning = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, first.getId()));
		var material = generationJobRepository.save(GenerationJobFixtures.material(userId, lesson.getId()));
		var other = courseRepository.save(CourseFixtures.single(userId));
		var otherJob = generationJobRepository.save(GenerationJobFixtures.replanCourse(userId, other.getId(), null));
		var independent = new TransactionTemplate(platformTransactionManager);
		independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		// when
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			var jobs = jpaCourseDeletionRepository.lockRoadmapJobs(roadmap.getId());
			// then — 반환한 모든 작업의 잠금이 호출자 트랜잭션에 남는다.
			assertThat(jobs).containsExactly(new JpaCourseDeletionRepository.DeletionJob(creation.getId(), false),
				new JpaCourseDeletionRepository.DeletionJob(replan.getId(), true),
				new JpaCourseDeletionRepository.DeletionJob(planning.getId(), true),
				new JpaCourseDeletionRepository.DeletionJob(material.getId(), true));
			for (var job : jobs) {
				assertThatThrownBy(() -> independent.executeWithoutResult(ignored -> jdbcTemplate.queryForObject(
					"SELECT id FROM generation_jobs WHERE id = ? FOR UPDATE NOWAIT", Long.class, job.id())))
					.rootCause().isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
			}
			Long unlockedId = independent.execute(ignored -> jdbcTemplate.queryForObject(
				"SELECT id FROM generation_jobs WHERE id = ? FOR UPDATE NOWAIT", Long.class, otherJob.getId()));
			assertThat(unlockedId).isEqualTo(otherJob.getId());
		});
	}

}
