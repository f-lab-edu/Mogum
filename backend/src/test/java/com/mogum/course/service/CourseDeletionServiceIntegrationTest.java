package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class CourseDeletionServiceIntegrationTest {
	@Autowired
	CourseDeletionService courseDeletionService;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(value = CourseStatus.class, names = {"REVIEWING", "LEARNING", "COMPLETED"})
	@DisplayName("단일 코스는 모든 상태에서 본문 없는 204로 지운다")
	void deletesSingleCourseInEveryState(CourseStatus status) {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(userId), status));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		var client = testRequests.asUser(userId);
		// when
		var response = client.delete().uri("/api/courses/" + course.getId()).exchange();
		// then
		response.expectStatus().isNoContent().expectBody().isEmpty();
		assertThat(courseRepository.findById(course.getId())).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons", Integer.class)).isZero();
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(CourseStatus.class)
	@DisplayName("로드맵 코스는 상태와 관계없이 지우기를 거절하고 모든 행을 보존한다")
	void rejectsRoadmapCourseWithoutChanges(CourseStatus status) {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var course = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "코스", "계획", List.of()), status));
		lessonRepository.save(LessonFixtures.lesson(course, 1));
		var job = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, course.getId()));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(
			tx -> creditLedger.consume(userId, job.getId(), 1));
		var before = snapshot();
		// when
		var response = testRequests.asUser(userId).delete().uri("/api/courses/" + course.getId()).exchange();
		// then
		response.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
			.isEqualTo("COURSE_DELETE_NOT_ALLOWED").jsonPath("$.message")
			.isEqualTo("로드맵의 코스는 로드맵을 지워야 해요");
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "경우={0}")
	@ValueSource(strings = {"FOREIGN", "MISSING", "DELETED"})
	@DisplayName("남의 코스와 없는 코스 및 두 번째 지우기는 404이고 남은 행을 바꾸지 않는다")
	void rejectsMissingOrForeignCourse(String scenario) {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.single(userId));
		long callerId = scenario.equals("FOREIGN")
			? userRepository.save(UserFixtures.approved("other@example.com")).getId() : userId;
		long targetId = scenario.equals("MISSING") ? Long.MAX_VALUE : course.getId();
		if (scenario.equals("DELETED")) {
			courseDeletionService.delete(userId, targetId);
		}
		var before = snapshot();
		// when
		var response = testRequests.asUser(callerId).delete().uri("/api/courses/" + targetId).exchange();
		// then
		response.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "자료={0}, 상태={1}, 기록={2}")
	@CsvSource({"false,QUEUED,OWED", "false,RUNNING,OWED", "false,FAILED,OWED",
		"true,QUEUED,OWED", "true,RUNNING,OWED", "true,FAILED,OWED",
		"false,FAILED,RESTORED", "true,FAILED,RESTORED", "false,QUEUED,PREVIOUS", "true,RUNNING,PREVIOUS",
		"false,SUCCEEDED,OWED", "true,SUCCEEDED,OWED"})
	@DisplayName("성공하지 않은 작업의 이번 주기 씀만 작업 지우기 전에 한 번 복구한다")
	void restoresOnlyUnsuccessfulCurrentConsumption(boolean material, JobStatus status, String history) {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.single(userId));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var job = generationJobRepository.save(material ? GenerationJobFixtures.material(userId, lesson.getId())
			: GenerationJobFixtures.replanCourse(userId, course.getId(), null));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			creditLedger.consume(userId, job.getId(), 1);
			if (history.equals("RESTORED")) {
				creditLedger.restoreIfOwed(userId, job.getId());
			}
		});
		if (history.equals("PREVIOUS")) {
			creditEntryRepository.save(CreditEntry.refill(userRepository.findById(userId).orElseThrow(), 0));
		}
		if (status == JobStatus.RUNNING) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
				testClock.instant().plusSeconds(360));
		} else if (status == JobStatus.FAILED) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		} else if (status == JobStatus.SUCCEEDED) {
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), material ? null : course.getId(), null,
				material ? lesson.getId() : null, testClock.instant());
		}
		int balance = userRepository.findById(userId).orElseThrow().getCreditBalance();
		// when
		courseDeletionService.delete(userId, course.getId());
		// then
		boolean owed = status != JobStatus.SUCCEEDED && history.equals("OWED");
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance())
			.isEqualTo(balance + (owed ? 1 : 0));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
			Integer.class)).isEqualTo(owed || history.equals("RESTORED") ? 1 : 0);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE job_id IS NOT NULL",
			Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT sum(amount) FROM credit_entries WHERE user_id = ?",
			Integer.class, userId)).isEqualTo(userRepository.findById(userId).orElseThrow().getCreditBalance());
		testRequests.asUser(userId).get().uri("/api/jobs/" + job.getId()).exchange().expectStatus().isNotFound()
			.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("코스 내용 지우기와 크레딧 복구는 호출자의 트랜잭션이 실패하면 함께 롤백된다")
	void rollsBackDeletionAndRestorationTogether() {
		// given
		long userId = user();
		var course = courseRepository.save(CourseFixtures.single(userId));
		var job = generationJobRepository.save(GenerationJobFixtures.replanCourse(userId, course.getId(), null));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(
			tx -> creditLedger.consume(userId, job.getId(), 1));
		var before = snapshot();
		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			courseDeletionService.deleteContent(userId, course.getId());
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(snapshot()).isEqualTo(before);
	}

	private long user() {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return user.getId();
	}

	private List<?> snapshot() {
		return List.of("users", "courses", "lessons", "materials", "roadmaps", "generation_jobs", "credit_entries")
			.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
