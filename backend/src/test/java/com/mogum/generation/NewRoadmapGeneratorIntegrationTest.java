package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.card.domain.CardConceptFixtures;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.config.CreditProperties;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestChatProvider;
import com.mogum.support.IntegrationTestConfiguration.TestEmbeddingProvider;
import com.mogum.support.IntegrationTestConfiguration.TestJobGenerator;
import com.mogum.support.IntegrationTestConfiguration.TestJobIntakeHandler;
import com.mogum.support.TestClock;
import com.mogum.worker.JobRunner;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NewRoadmapGeneratorIntegrationTest {
	@Autowired
	EntityManager entityManager;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	WorkerProperties workerProperties;
	@Autowired
	CreditProperties creditProperties;
	@Autowired
	TestClock testClock;
	@Autowired
	List<JobGenerator> generators;
	@Autowired
	TestChatProvider testChatProvider;
	@Autowired
	TestEmbeddingProvider testEmbeddingProvider;
	@Autowired
	ConceptRepository conceptRepository;
	@Autowired
	JobIntakeService jobIntakeService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CreditEntryRepository creditEntryRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	ApplicationContext applicationContext;

	@BeforeEach
	void prepareAdapters() {
		testChatProvider.reset();
		testEmbeddingProvider.reset();
	}

	@Test
	@DisplayName("운영 로드맵 처리기는 입력과 계획을 저장하고 모든 코스를 강의 없는 짜기 전으로 만든다")
	void createsRoadmapAndUnplannedCourses(CapturedOutput output) throws InterruptedException {
		// given
		testChatProvider.reset();
		var user = userRepository.save(UserFixtures.approved(
			"me@example.com", 100, testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		long id = jobIntakeService.receive(user.getId(), new JobRequest(JobType.NEW_ROADMAP, null,
			new LearningInput("자바", Level.OTHER, "조금 알아요", Purpose.OTHER, "연구", "원하는 메모"), null)).id();
		var scheduler = Executors.newSingleThreadScheduledExecutor();
		var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
			workerProperties, generators, scheduler);
		try {
			// when
			boolean processed = runner.runOnce();

			// then
			assertThat(processed).isTrue();
			assertThat(output.getAll()).doesNotContain("job generate failed");
			assertThat(testChatProvider.outsideTransaction(id)).isTrue();
			assertThat(testEmbeddingProvider.requests(id)).isEmpty();
			assertThat(testChatProvider.requests(id)).singleElement()
				.satisfies(request -> assertThat(request.messages().get(1).text()).doesNotContain("relatedConcepts"));
			var job = jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id);
			assertThat(job).containsEntry("status", "SUCCEEDED");
			var roadmap = jdbcTemplate.queryForMap("SELECT * FROM roadmaps WHERE id = ?", job.get("roadmap_id"));
			assertThat(roadmap).containsEntry("status", "REVIEWING").containsEntry("title", "자바 로드맵")
				.containsEntry("description", "자바를 익힌다").containsEntry("user_id", user.getId())
				.containsEntry("topic", "자바").containsEntry("level", "OTHER")
				.containsEntry("level_other", "조금 알아요").containsEntry("purpose", "OTHER")
				.containsEntry("purpose_other", "연구").containsEntry("memo", "원하는 메모");
			var courses = jdbcTemplate.queryForList("SELECT * FROM courses ORDER BY position");
			assertThat(courses).extracting(row -> row.get("position")).containsExactly(1, 2);
			assertThat(courses).extracting(row -> row.get("title")).containsExactly("기초", "응용");
			assertThat(courses).extracting(row -> row.get("plan_description"))
				.containsExactly("기초 설명", "응용 설명");
			assertThat(courses).allSatisfy(row -> assertThat(row)
				.containsEntry("mastery_report", null).containsEntry("status", "NOT_PLANNED")
				.containsEntry("user_id", user.getId())
				.containsEntry("roadmap_id", job.get("roadmap_id"))
				.containsEntry("topic", null).containsEntry("level", null).containsEntry("level_other", null)
				.containsEntry("purpose", null).containsEntry("purpose_other", null).containsEntry("memo", null));
			assertThat(jdbcTemplate.queryForList(
				"SELECT plan_concepts -> 0 ->> 'name' FROM courses ORDER BY position", String.class))
				.containsExactly("변수", "함수");
			assertThat(jdbcTemplate.queryForList(
				"SELECT plan_concepts -> 0 ->> 'definition' FROM courses ORDER BY position", String.class))
				.containsExactly("값을 담는 이름", "동작의 묶음");
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons", Integer.class)).isZero();
			assertThat(jdbcTemplate.queryForMap("SELECT * FROM llm_calls"))
				.containsEntry("job_id", id).containsEntry("user_id", user.getId())
				.containsEntry("job_type", "NEW_ROADMAP").containsEntry("outcome", "SUCCEEDED");
			assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance())
				.isEqualTo(100 - creditProperties.newRoadmap());
		} finally {
			scheduler.shutdownNow();
			assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	@DisplayName("공용 통합 컨텍스트에는 테스트 받기와 생성 처리기 빈이 없고 운영 처리기만 있다")
	void sharesProductionHandlersOnly() {
		// when
		var intake = applicationContext.getBeansOfType(JobIntakeHandler.class).values();
		var generation = applicationContext.getBeansOfType(JobGenerator.class).values();

		// then
		assertThat(intake).noneMatch(TestJobIntakeHandler.class::isInstance);
		assertThat(generation).noneMatch(TestJobGenerator.class::isInstance);
		assertThat(applicationContext.getBeansOfType(TestJobIntakeHandler.class)).isEmpty();
		assertThat(applicationContext.getBeansOfType(TestJobGenerator.class)).isEmpty();
		assertThat(intake).extracting(JobIntakeHandler::type)
			.contains(JobType.NEW_COURSE, JobType.NEW_ROADMAP);
		assertThat(generation).extracting(JobGenerator::type)
			.contains(JobType.NEW_COURSE, JobType.NEW_ROADMAP);
	}

	@Test
	@DisplayName("로드맵 계획은 주제와 목적 질의로 관련 개념과 판정을 준비한 뒤 트랜잭션 밖에서 채팅한다")
	void suppliesRelatedConceptsBeforeChat(CapturedOutput output) throws InterruptedException {
		// given
		long id = receive();
		long userId = jdbcTemplate.queryForObject("SELECT user_id FROM generation_jobs WHERE id = ?", Long.class, id);
		var known = conceptRepository.save(ConceptFixtures.concept(userId, "LEAKCHECK-known", "LEAKCHECK-definition",
			new float[] {1, 0}));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
			var course = CourseFixtures.single(userId,
				new LearningInput("다른 주제", Level.BEGINNER, null, Purpose.WORK, null, null), "다른 코스");
			entityManager.persist(course);
			var lesson = LessonFixtures.lesson(course, 1);
			entityManager.persist(lesson);
			var card = CardFixtures.card(userId, lesson.getId(), 1, "질문", "답");
			card.applyReview(new FsrsResult(FsrsState.REVIEW, null, 30.0, 5.0,
				testClock.instant().plus(Duration.ofDays(21)), testClock.instant()));
			entityManager.persist(card);
			entityManager.persist(CardConceptFixtures.link(card, known));
			entityManager.persist(ReviewFixtures.review(card, Rating.GOOD));
		});
		conceptRepository.save(ConceptFixtures.concept(userId, "먼 개념", "먼 정의", new float[] {-1, 0}));
		testEmbeddingProvider.vector("LEAKCHECK-topic\nLEAKCHECK-purpose", List.of(1f, 0f));

		// when
		runOnce();

		// then
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "SUCCEEDED");
		assertThat(testEmbeddingProvider.requests(id)).singleElement().satisfies(
			request -> assertThat(request.definitions()).containsExactly("LEAKCHECK-topic\nLEAKCHECK-purpose"));
		assertThat(testChatProvider.requests(id)).singleElement().satisfies(request -> {
			var data = new JsonMapper().readTree(request.messages().get(1).text().split("\n", 3)[1]);
			var related = data.path("relatedConcepts");
			assertThat(related).hasSize(2);
			assertThat(related.get(0).path("name").asString()).isEqualTo("LEAKCHECK-known");
			assertThat(related.get(0).path("definition").asString()).isEqualTo("LEAKCHECK-definition");
			assertThat(related.get(0).path("instruction").asString()).isEqualTo("짧게 다룬다.");
			assertThat(related.get(1).path("name").asString()).isEqualTo("먼 개념");
			assertThat(related.get(1).path("instruction").asString()).isEqualTo("처음 배우는 것으로 보고 자세히 다룬다.");
		});
		assertThat(testEmbeddingProvider.outsideTransaction(id)).isTrue();
		assertThat(testChatProvider.outsideTransaction(id)).isTrue();
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.containsExactly("EMBEDDING", "CHAT");
		assertThat(output.getAll()).doesNotContain("job generate failed", "LEAKCHECK-");
	}

	@ParameterizedTest(name = "임베딩 실패={0}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("로드맵 관련 개념 임베딩 실패는 채팅 없이 같은 실패와 호출 기록을 남기고 기존 결과를 보존한다")
	void failsRelatedEmbeddingBeforeChat(GenerationOutcome.Failure failure, CapturedOutput output)
		throws InterruptedException {
		// given
		long id = receive();
		long userId = jdbcTemplate.queryForObject("SELECT user_id FROM generation_jobs WHERE id = ?", Long.class, id);
		var before = List.of("roadmaps", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
		conceptRepository.save(ConceptFixtures.concept(userId, "LEAKCHECK-concept", "LEAKCHECK-definition",
			new float[] {1, 0}));
		var replies = new EmbeddingProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestEmbeddingProvider.failure(failure));
		testEmbeddingProvider.reply(id, replies);

		// when
		runOnce();

		// then
		int attempts = failure == GenerationOutcome.Failure.INVALID_OUTPUT
			|| failure == GenerationOutcome.Failure.PROVIDER_UNAVAILABLE ? workerProperties.maxRetries() + 1 : 1;
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "FAILED").containsEntry("error_type", failure.name());
		assertThat(testChatProvider.requests(id)).isEmpty();
		assertThat(testEmbeddingProvider.requests(id)).hasSize(attempts);
		assertThat(testEmbeddingProvider.outsideTransaction(id)).isTrue();
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.hasSize(attempts).containsOnly("EMBEDDING");
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls ORDER BY id", String.class))
			.containsOnly(TestEmbeddingProvider.failure(failure).outcome().name());
		assertThat(List.of("roadmaps", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList())
			.isEqualTo(before);
		assertThat(output.getAll()).doesNotContain("job generate failed", "LEAKCHECK-");
	}

	private long receive() {
		var user = userRepository.save(UserFixtures.approved("me@example.com", 100,
			testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return jobIntakeService.receive(user.getId(), new JobRequest(JobType.NEW_ROADMAP, null,
			new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER,
				"LEAKCHECK-purpose", "LEAKCHECK-memo"),
			null)).id();
	}

	private boolean runOnce() throws InterruptedException {
		var scheduler = Executors.newSingleThreadScheduledExecutor();
		try {
			return new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
				workerProperties, generators, scheduler).runOnce();
		} finally {
			scheduler.shutdownNow();
			assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}
}
