package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import com.mogum.card.service.TodayReviewService;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.MasteryReport;
import com.mogum.course.domain.Material;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.service.LessonCommandService;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class CourseControllerDeletionIntegrationTest {
	@Autowired
	CourseRepository courseRepository;
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
	TodayReviewService todayReviewService;
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
	@DisplayName("작업부터 코스까지 지우고 카드의 학습과 개념 및 비용 기록은 연결만 끊어 보존한다")
	void deletesCourseContentWhilePreservingMemoryAndAccounting() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		long userId = user.getId();
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var course = CourseFixtures.single(userId);
		course.replanLessons(new MasteryReport(List.of("짧게"), List.of("다시")));
		course.start(testClock.instant());
		courseRepository.save(course);
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		lessonRepository.save(LessonFixtures.lesson(course, 2));
		materialRepository.save(Material.write(lesson, "강의 본문"));
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(tx -> lessonCardWriter.writeLessonCards(
				userId, lesson.getId(), List.of(new NewCard("질문", "답",
					List.of(new NewConcept("변수", "값의 이름", new float[] {1, 0}))))));
		lessonCommandService.complete(userId, lesson.getId());
		long cardId = jdbcTemplate.queryForObject("SELECT id FROM cards", Long.class);
		cardCommandService.review(userId, cardId, Rating.AGAIN);
		var cardBefore = cardRepository.findById(cardId).orElseThrow();
		var memoryBefore = List.of("concepts", "card_concepts", "reviews").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
		var creation = generationJobRepository.save(GenerationJobFixtures.newCourse(userId, course.getInput()));
		var material = generationJobRepository.save(GenerationJobFixtures.material(userId, lesson.getId()));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx -> {
			creditLedger.consume(userId, creation.getId(), 2);
			creditLedger.consume(userId, material.getId(), 3);
		});
		GenerationJobFixtures.succeeded(jdbcTemplate, creation.getId(), course.getId(), null, null,
			testClock.instant());
		GenerationJobFixtures.succeeded(jdbcTemplate, material.getId(), null, null, lesson.getId(),
			testClock.instant());
		long callId = llmCallWriter.start(material.getId(), userId, JobType.MATERIAL, LlmCallType.CHAT, "test-model");
		llmCallWriter.finish(callId, LlmCallOutcome.SUCCEEDED, 100, 20, new BigDecimal("0.01"), 50);
		var callsBefore = jdbcTemplate.queryForMap("SELECT * FROM llm_calls WHERE id = ?", callId);
		callsBefore.remove("job_id");
		var entriesBefore = jdbcTemplate
			.queryForList("SELECT id, type, amount, user_id FROM credit_entries ORDER BY id");
		var otherCourse = courseRepository.save(CourseFixtures.single(userId));
		var otherLesson = lessonRepository.save(LessonFixtures.lesson(otherCourse, 1));
		var otherCard = cardRepository.save(CardFixtures.card(userId, otherLesson.getId(), 1, "다른 질문", "다른 답"));
		var otherBefore = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", otherCourse.getId());
		testClock.advance(Duration.ofSeconds(5));
		var client = testRequests.asUser(userId);
		assertThat(todayReviewService.count(userId, null)).isEqualTo(1);
		// when
		client.delete().uri("/api/courses/" + course.getId()).exchange()
			.expectStatus().isNoContent().expectBody().isEmpty();
		// then
		assertThat(courseRepository.findById(course.getId())).isEmpty();
		assertThat(lessonRepository.findById(lesson.getId())).isEmpty();
		assertThat(materialRepository.findByLessonId(lesson.getId())).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons WHERE course_id = ?", Integer.class,
			course.getId())).isZero();
		var saved = cardRepository.findById(cardId).orElseThrow();
		assertThat(saved.getStatus().name()).isEqualTo("COURSE_DELETED");
		assertThat(saved.getLessonId()).isNull();
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved).usingRecursiveComparison().ignoringFields("status", "lessonId", "updatedAt")
			.isEqualTo(cardBefore);
		assertThat(List.of("concepts", "card_concepts", "reviews").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList()).isEqualTo(memoryBefore);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM llm_calls WHERE id = ?", callId))
			.containsEntry("job_id", null).containsEntry("user_id", userId)
			.containsAllEntriesOf(callsBefore);
		assertThat(jdbcTemplate.queryForList("SELECT id, type, amount, user_id FROM credit_entries ORDER BY id"))
			.isEqualTo(entriesBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE job_id IS NOT NULL",
			Integer.class)).isZero();
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(95);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", otherCourse.getId()))
			.isEqualTo(otherBefore);
		assertThat(cardRepository.findById(otherCard.getId()).orElseThrow()).usingRecursiveComparison()
			.isEqualTo(otherCard);
		assertThat(todayReviewService.read(userId, null).cards()).isEmpty();
		client.get().uri("/api/home/reviews").exchange().expectStatus().isOk()
			.expectBody().jsonPath("$.data.remainingCount").isEqualTo(0);
		client.get().uri("/api/jobs/" + material.getId()).exchange().expectStatus().isNotFound();
	}
}
