package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.Rating;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.service.CardCommandService;
import com.mogum.card.service.LessonCardWriter;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Material;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class RoadmapDeletionServiceIntegrationTest {
	@Autowired
	RoadmapDeletionService roadmapDeletionService;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	LessonCardWriter lessonCardWriter;
	@Autowired
	CardCommandService cardCommandService;
	@Autowired
	LessonCommandService lessonCommandService;
	@Autowired
	LlmCallWriter llmCallWriter;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@Test
	@DisplayName("진행 중 로드맵의 모든 코스와 작업을 지우고 카드 기억과 비용 기록 및 다른 결과는 보존한다")
	void deletesAllRoadmapContentWhilePreservingMemoryAndAccounting() {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
			RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		var learning = course(roadmap, 1, CourseStatus.LEARNING);
		var reviewing = course(roadmap, 2, CourseStatus.REVIEWING);
		var unplanned = course(roadmap, 3, CourseStatus.NOT_PLANNED);
		var lesson = lessonRepository.save(LessonFixtures.lesson(learning, 1));
		lessonRepository.save(LessonFixtures.lesson(learning, 2));
		lessonRepository.save(LessonFixtures.lesson(reviewing, 1));
		materialRepository.save(Material.write(lesson, "강의 본문"));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(
			tx -> lessonCardWriter.writeLessonCards(userId, lesson.getId(), List.of(new NewCard("질문", "답",
				List.of(new NewConcept("변수", "값의 이름", new float[] {1, 0}))))));
		lessonCommandService.complete(userId, lesson.getId());
		long cardId = jdbcTemplate.queryForObject("SELECT id FROM cards", Long.class);
		cardCommandService.review(userId, cardId, Rating.AGAIN);
		var cardBefore = cardRepository.findById(cardId).orElseThrow();
		var memoryBefore = memory();
		var creation = generationJobRepository.save(GenerationJobFixtures.newRoadmap(userId, roadmap.getInput()));
		var planning = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, learning.getId()));
		var material = generationJobRepository.save(GenerationJobFixtures.material(userId, lesson.getId()));
		var jobs = List.of(creation, planning, material);
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			for (var job : jobs) {
				creditLedger.consume(userId, job.getId(), 2);
			}
		});
		GenerationJobFixtures.succeeded(jdbcTemplate, creation.getId(), null, roadmap.getId(), null,
			testClock.instant());
		GenerationJobFixtures.succeeded(jdbcTemplate, planning.getId(), learning.getId(), null, null,
			testClock.instant());
		GenerationJobFixtures.succeeded(jdbcTemplate, material.getId(), null, null, lesson.getId(),
			testClock.instant());
		for (var job : jobs) {
			long callId = llmCallWriter.start(job.getId(), userId, job.getType(), LlmCallType.CHAT, "test-model");
			llmCallWriter.finish(callId, LlmCallOutcome.SUCCEEDED, 100, 20, new BigDecimal("0.01"), 50);
		}
		var callsBefore = jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id");
		callsBefore.forEach(row -> row.remove("job_id"));
		var entriesBefore = jdbcTemplate.queryForList("SELECT * FROM credit_entries ORDER BY id");
		entriesBefore.forEach(row -> row.remove("job_id"));
		var other = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var otherCourse = course(other, 1, CourseStatus.REVIEWING);
		var single = courseRepository.save(CourseFixtures.single(userId));
		var otherLesson = lessonRepository.save(LessonFixtures.lesson(otherCourse, 1));
		var singleLesson = lessonRepository.save(LessonFixtures.lesson(single, 1));
		var otherCard = cardRepository.save(CardFixtures.card(userId, otherLesson.getId(), 1, "다른 질문", "다른 답"));
		var otherJob = generationJobRepository
			.save(GenerationJobFixtures.planRoadmapCourse(userId, otherCourse.getId()));
		var singleJob = generationJobRepository.save(GenerationJobFixtures.material(userId, singleLesson.getId()));
		var preservedIds = Map.of("roadmaps", List.of(other.getId()),
			"courses", List.of(otherCourse.getId(), single.getId()),
			"lessons", List.of(otherLesson.getId(), singleLesson.getId()),
			"cards", List.of(otherCard.getId()), "generation_jobs", List.of(otherJob.getId(), singleJob.getId()));
		var otherBefore = preservedRows(preservedIds);
		testClock.advance(Duration.ofSeconds(5));
		var client = testRequests.asUser(userId);
		// when
		var response = client.delete().uri("/api/roadmaps/" + roadmap.getId()).exchange();
		// then
		response.expectStatus().isNoContent().expectBody().isEmpty();
		assertThat(roadmapRepository.findById(roadmap.getId())).isEmpty();
		for (var course : List.of(learning, reviewing, unplanned)) {
			assertThat(courseRepository.findById(course.getId())).isEmpty();
			assertThat(lessonRepository.findByCourseIdOrderByPositionAsc(course.getId())).isEmpty();
			client.get().uri("/api/courses/" + course.getId()).exchange().expectStatus().isNotFound()
				.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM materials", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT id FROM generation_jobs ORDER BY id", Long.class))
			.containsExactly(otherJob.getId(), singleJob.getId());
		var saved = cardRepository.findById(cardId).orElseThrow();
		assertThat(saved.getStatus().name()).isEqualTo("COURSE_DELETED");
		assertThat(saved.getLessonId()).isNull();
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved).usingRecursiveComparison().ignoringFields("status", "lessonId", "updatedAt")
			.isEqualTo(cardBefore);
		assertThat(memory()).isEqualTo(memoryBefore);
		var callsAfter = jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id");
		assertThat(callsAfter).allSatisfy(row -> assertThat(row).containsEntry("job_id", null));
		callsAfter.forEach(row -> row.remove("job_id"));
		assertThat(callsAfter).isEqualTo(callsBefore);
		var entriesAfter = jdbcTemplate.queryForList("SELECT * FROM credit_entries ORDER BY id");
		assertThat(entriesAfter).allSatisfy(row -> assertThat(row).containsEntry("job_id", null));
		entriesAfter.forEach(row -> row.remove("job_id"));
		assertThat(entriesAfter).isEqualTo(entriesBefore);
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(94);
		assertThat(preservedRows(preservedIds)).isEqualTo(otherBefore);
		assertThat(roadmapRepository.findById(other.getId())).isPresent();
		assertThat(courseRepository.findById(otherCourse.getId())).isPresent();
		assertThat(courseRepository.findById(single.getId())).isPresent();
		client.get().uri("/api/roadmaps/" + roadmap.getId()).exchange().expectStatus().isNotFound()
			.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		for (var job : jobs) {
			client.get().uri("/api/jobs/" + job.getId()).exchange().expectStatus().isNotFound()
				.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		}
	}

	@ParameterizedTest(name = "로드맵={0}")
	@EnumSource(RoadmapStatus.class)
	@DisplayName("검토 중의 짜기 전 코스와 다 마친 코스까지 상태와 상관없이 함께 지운다")
	void deletesRoadmapInEveryState(RoadmapStatus status) {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId), status));
		var courseStatus = switch (status) {
			case REVIEWING -> CourseStatus.NOT_PLANNED;
			case IN_PROGRESS -> CourseStatus.LEARNING;
			case COMPLETED -> CourseStatus.COMPLETED;
		};
		course(roadmap, 1, courseStatus);
		course(roadmap, 2, courseStatus);
		// when
		var response = testRequests.asUser(userId).delete().uri("/api/roadmaps/" + roadmap.getId()).exchange();
		// then
		response.expectStatus().isNoContent().expectBody().isEmpty();
		assertThat(roadmapRepository.findById(roadmap.getId())).isEmpty();
		assertThat(courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId())).isEmpty();
	}

	@ParameterizedTest(name = "경우={0}")
	@ValueSource(strings = {"FOREIGN", "MISSING", "DELETED"})
	@DisplayName("남의 로드맵과 없는 로드맵 및 두 번째 지우기는 404이고 행을 바꾸지 않는다")
	void rejectsMissingOrForeignRoadmap(String scenario) {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		course(roadmap, 1, CourseStatus.NOT_PLANNED);
		long callerId = scenario.equals("FOREIGN")
			? userRepository.save(UserFixtures.approved("other@example.com")).getId() : userId;
		long targetId = scenario.equals("MISSING") ? Long.MAX_VALUE : roadmap.getId();
		if (scenario.equals("DELETED")) {
			roadmapDeletionService.delete(userId, targetId);
		}
		var before = snapshot();
		// when
		var response = testRequests.asUser(callerId).delete().uri("/api/roadmaps/" + targetId).exchange();
		// then
		response.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "종류={0}, 상태={1}")
	@CsvSource({"REPLAN_ROADMAP,QUEUED", "REPLAN_ROADMAP,RUNNING", "REPLAN_ROADMAP,FAILED",
		"REPLAN_ROADMAP,SUCCEEDED", "PLAN_ROADMAP_COURSE,QUEUED", "PLAN_ROADMAP_COURSE,RUNNING",
		"PLAN_ROADMAP_COURSE,FAILED", "PLAN_ROADMAP_COURSE,SUCCEEDED", "MATERIAL,QUEUED", "MATERIAL,RUNNING",
		"MATERIAL,FAILED", "MATERIAL,SUCCEEDED"})
	@DisplayName("로드맵과 코스 및 강의의 성공하지 않은 작업만 복구하고 잔액과 기록의 합을 맞춘다")
	void restoresOnlyUnsuccessfulJobs(JobType type, JobStatus status) {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId),
			type == JobType.REPLAN_ROADMAP ? RoadmapStatus.REVIEWING : RoadmapStatus.IN_PROGRESS));
		var course = course(roadmap, 1, type == JobType.MATERIAL ? CourseStatus.LEARNING
			: status == JobStatus.SUCCEEDED ? CourseStatus.REVIEWING : CourseStatus.NOT_PLANNED);
		Long lessonId = type == JobType.MATERIAL
			? lessonRepository.save(LessonFixtures.lesson(course, 1)).getId() : null;
		GenerationJob job = generationJobRepository.save(switch (type) {
			case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(userId, roadmap.getId(), null);
			case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(userId, course.getId());
			case MATERIAL -> GenerationJobFixtures.material(userId, lessonId);
			default -> throw new IllegalArgumentException("Unexpected job type");
		});
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(tx -> creditLedger.consume(userId, job.getId(), 3));
		if (status == JobStatus.RUNNING) {
			GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
				testClock.instant().plusSeconds(360));
		} else if (status == JobStatus.FAILED) {
			GenerationJobFixtures.failed(jdbcTemplate, job.getId(), JobErrorType.INVALID_OUTPUT, testClock.instant());
		} else if (status == JobStatus.SUCCEEDED) {
			GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(),
				type == JobType.PLAN_ROADMAP_COURSE ? course.getId() : null,
				type == JobType.REPLAN_ROADMAP ? roadmap.getId() : null,
				type == JobType.MATERIAL ? lessonId : null, testClock.instant());
		}
		// when
		roadmapDeletionService.delete(userId, roadmap.getId());
		// then
		int balance = status == JobStatus.SUCCEEDED ? 97 : 100;
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(balance);
		assertThat(jdbcTemplate.queryForObject("SELECT sum(amount) FROM credit_entries WHERE user_id = ?",
			Integer.class, userId)).isEqualTo(balance);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
			Integer.class)).isEqualTo(status == JobStatus.SUCCEEDED ? 0 : 1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE job_id IS NOT NULL",
			Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
		testRequests.asUser(userId).get().uri("/api/jobs/" + job.getId()).exchange().expectStatus().isNotFound()
			.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("로드맵 지우기와 모든 코스 내용 및 크레딧 복구는 같은 트랜잭션으로 롤백된다")
	void rollsBackDeletionAndRestorationTogether() {
		// given
		long userId = user();
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
		var job = generationJobRepository.save(GenerationJobFixtures.planRoadmapCourse(userId, course.getId()));
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(tx -> creditLedger.consume(userId, job.getId(), 1));
		var before = snapshot();
		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			roadmapDeletionService.delete(userId, roadmap.getId());
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(snapshot()).isEqualTo(before);
	}

	private List<?> memory() {
		return List.of("concepts", "card_concepts", "reviews").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
	}

	private List<?> preservedRows(Map<String, List<Long>> ids) {
		return ids.entrySet().stream()
			.map(entry -> entry.getValue().stream()
				.map(id -> jdbcTemplate.queryForMap("SELECT * FROM " + entry.getKey() + " WHERE id = ?", id)).toList())
			.toList();
	}

	private Course course(Roadmap roadmap, int position,
		CourseStatus status) {
		return courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, position, "코스", "계획", List.of()), status));
	}

	private long user() {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return user.getId();
	}

	private List<?> snapshot() {
		return List.of("users", "roadmaps", "courses", "lessons", "materials", "generation_jobs", "credit_entries")
			.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
