package com.mogum.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Rating;
import com.mogum.card.service.CardCommandService;
import com.mogum.card.service.LessonCardWriter;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Material;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.course.service.CourseDeletionService;
import com.mogum.course.service.LessonCommandService;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestSessions;

@IntegrationTest
class WithdrawServiceIntegrationTest {
	private static final List<String> TABLES = List.of("generation_jobs", "reviews", "card_concepts", "cards",
		"materials", "lessons", "courses", "roadmaps", "concepts", "credit_entries", "users");
	@Autowired
	WithdrawService withdrawService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	LessonCardWriter lessonCardWriter;
	@Autowired
	LessonCommandService lessonCommandService;
	@Autowired
	CardCommandService cardCommandService;
	@Autowired
	CourseDeletionService courseDeletionService;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	LlmCallWriter llmCallWriter;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	JdbcIndexedSessionRepository jdbcIndexedSessionRepository;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("탈퇴의 데이터와 모든 기기 세션 삭제는 한 트랜잭션으로 롤백된다")
	void rollsBackAllDeletionTogether() {
		// given
		long userId = content("me@example.com");
		var sessions = new TestSessions(jdbcIndexedSessionRepository);
		var cookie = sessions.create(userId);
		var before = snapshot();
		var calls = jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id");
		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			withdrawService.withdraw(userId);
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(snapshot()).isEqualTo(before);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id")).isEqualTo(calls);
		assertThat(jdbcIndexedSessionRepository.findById(sessions.id(cookie))).isNotNull();
	}

	private long content(String email) {
		var user = userRepository.save(UserFixtures.approved(email));
		long userId = user.getId();
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(userId));
		var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.inRoadmap(roadmap, 1,
			"코스", "계획", List.of()), CourseStatus.LEARNING));
		var single = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(userId),
			CourseStatus.LEARNING));
		var deleted = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(userId),
			CourseStatus.LEARNING));
		courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "짜기 전", "계획", List.of()));
		for (var parent : List.of(single, course, deleted)) {
			var lesson = lessonRepository.save(LessonFixtures.lesson(parent, 1));
			materialRepository.save(Material.write(lesson, "강의 본문"));
			new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> lessonCardWriter
				.writeLessonCards(userId, lesson.getId(), List.of(new NewCard("질문", "답", List.of(
					new NewConcept("개념", "정의", new float[] {1, 0}))))));
			lessonCommandService.complete(userId, lesson.getId());
			long cardId = jdbcTemplate.queryForObject("SELECT id FROM cards WHERE lesson_id = ?", Long.class,
				lesson.getId());
			cardCommandService.review(userId, cardId, Rating.AGAIN);
		}
		courseDeletionService.delete(userId, deleted.getId());
		for (var status : JobStatus.values()) {
			var job = GenerationJobFixtures.newCourse(userId, single.getInput());
			if (status == JobStatus.FAILED) {
				GenerationJobFixtures.failed(job, JobErrorType.INVALID_OUTPUT);
			}
			job = generationJobRepository.save(job);
			if (status == JobStatus.RUNNING) {
				GenerationJobFixtures.running(jdbcTemplate, job.getId(), 1, testClock.instant(),
					testClock.instant().plusSeconds(600));
			} else if (status == JobStatus.SUCCEEDED) {
				GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), course.getId(), null, null,
					testClock.instant());
			}
			long jobId = job.getId();
			new TransactionTemplate(platformTransactionManager)
				.executeWithoutResult(tx -> creditLedger.consume(userId, jobId, 1));
			long callId = llmCallWriter.start(jobId, userId, job.getType(), LlmCallType.CHAT, "test-model");
			llmCallWriter.finish(callId, LlmCallOutcome.SUCCEEDED, 100, 20, new BigDecimal("0.01"), 50);
		}
		return userId;
	}

	private List<List<Map<String, Object>>> snapshot() {
		return TABLES.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2"))
			.toList();
	}

}
