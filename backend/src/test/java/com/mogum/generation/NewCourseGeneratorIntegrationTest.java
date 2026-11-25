package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.CreditProperties;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestChatProvider;
import com.mogum.support.IntegrationTestConfiguration.TestEmbeddingProvider;
import com.mogum.support.TestClock;
import com.mogum.worker.JobRunner;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NewCourseGeneratorIntegrationTest {
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
	private ScheduledExecutorService renewals;

	@BeforeEach
	void prepareAdapter() {
		testChatProvider.reset();
		testEmbeddingProvider.reset();
		renewals = Executors.newSingleThreadScheduledExecutor();
	}

	@AfterEach
	void stopScheduler() throws InterruptedException {
		renewals.shutdownNow();
		assertThat(renewals.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	@DisplayName("운영 새 코스 처리기는 검토 중 코스와 순서 있는 강의와 입력과 호출 기록을 함께 남긴다")
	void createsCourseOnlyOnSuccess(CapturedOutput output) {
		// given
		long id = receive(JobType.NEW_COURSE);
		assertNoResults();

		// when
		boolean processed = runner().runOnce();

		// then
		assertThat(processed).isTrue();
		var job = jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id);
		assertThat(job).containsEntry("status", "SUCCEEDED");
		var course = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", job.get("course_id"));
		assertThat(course).containsEntry("status", "REVIEWING").containsEntry("title", "자바 코스")
			.containsEntry("user_id", job.get("user_id")).containsEntry("roadmap_id", null);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT mastery_report = '{\"shortened\":[],\"revisited\":[]}'::jsonb FROM courses",
			Boolean.class)).isTrue();
		assertInput(course);
		var lessons = jdbcTemplate.queryForList("SELECT * FROM lessons ORDER BY position");
		assertThat(lessons).extracting(row -> row.get("position")).containsExactly(1, 2);
		assertThat(lessons).extracting(row -> row.get("title")).containsExactly("기초", "응용");
		assertThat(lessons).extracting(row -> row.get("description")).containsExactly("기초 설명", "응용 설명");
		assertThat(lessons).allSatisfy(row -> assertThat(row.get("course_id")).isEqualTo(job.get("course_id")));
		assertThat(jdbcTemplate.queryForList(
			"SELECT concepts -> 0 ->> 'name' FROM lessons ORDER BY position", String.class))
			.containsExactly("변수", "함수");
		assertThat(jdbcTemplate.queryForList(
			"SELECT concepts -> 0 ->> 'definition' FROM lessons ORDER BY position", String.class))
			.containsExactly("값을 담는 이름", "동작의 묶음");
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM llm_calls"))
			.containsEntry("job_id", id).containsEntry("user_id", job.get("user_id"))
			.containsEntry("job_type", "NEW_COURSE").containsEntry("call_type", "CHAT")
			.containsEntry("outcome", "SUCCEEDED");
		assertThat(balance()).isEqualTo(100 - creditProperties.newCourse());
		var request = testChatProvider.requests(id).getFirst();
		assertThat(request.jobType()).isEqualTo(JobType.NEW_COURSE);
		assertThat(testEmbeddingProvider.requests(id)).isEmpty();
		assertThat(testChatProvider.requests(id).getFirst().messages().get(1).text())
			.doesNotContain("relatedConcepts");
		assertThat(testChatProvider.outsideTransaction(id)).isTrue();
		assertThat(request.messages().get(1).text()).contains("LEAKCHECK-topic", "LEAKCHECK-level",
			"LEAKCHECK-purpose", "LEAKCHECK-memo");
		assertThat(output.getAll()).doesNotContain("job generate failed", "LEAKCHECK-", "값을 담는 이름", "자바 코스");
	}

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"NEW_COURSE", "NEW_ROADMAP"})
	@DisplayName("잘린 응답은 이어 붙여 성공하고 두 호출을 각각 기록한다")
	void continuesTruncatedOutput(JobType type) {
		// given
		long id = receive(type);
		String text = TestChatProvider.defaultText(type);
		int split = text.length() / 2;
		testChatProvider.reply(id, new ChatProvider.Reply(text.substring(0, split), LlmCallOutcome.TRUNCATED,
			null, new ChatProvider.Usage(10, 0, 10)), TestChatProvider.success(text.substring(split)));

		// when
		runner().runOnce();

		// then
		assertThat(status(id)).isEqualTo("SUCCEEDED");
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.containsExactly("CHAT", "CONTINUATION");
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls ORDER BY id", String.class))
			.containsExactly("TRUNCATED", "SUCCEEDED");
		var requests = testChatProvider.requests(id);
		assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request.jobId()).isEqualTo(id));
		assertThat(requests.get(1).messages().get(2))
			.isEqualTo(new ChatProvider.Message(ChatProvider.Role.ASSISTANT, text.substring(0, split)));
	}

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"NEW_COURSE", "NEW_ROADMAP"})
	@DisplayName("형식 불합격은 정한 횟수만 재시도하고 결과 없이 실패와 복구를 남기며 버려도 결과가 없다")
	void failsInvalidOutputWithoutResults(JobType type, CapturedOutput output) {
		// given
		long id = receive(type);
		var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestChatProvider.success("LEAKCHECK-invalid-output"));
		testChatProvider.reply(id, replies);

		// when
		runner().runOnce();

		// then
		assertFailure(id, "INVALID_OUTPUT", workerProperties.maxRetries() + 1);
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls", String.class))
			.containsOnly("INVALID_OUTPUT");
		assertThat(output.getAll()).doesNotContain("LEAKCHECK-");

		// when
		jobIntakeService.discard(owner(id), id);

		// then
		assertNoResults();
		assertThat(balance()).isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForList("SELECT job_id FROM llm_calls", Long.class)).containsOnlyNulls();
	}

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"NEW_COURSE", "NEW_ROADMAP"})
	@DisplayName("일시적 실패 뒤에는 다시 호출해 성공한다")
	void retriesTemporaryFailure(JobType type, CapturedOutput output) {
		// given
		long id = receive(type);
		testChatProvider.reply(id, TestChatProvider.failure(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));

		// when
		runner().runOnce();

		// then
		assertThat(status(id)).isEqualTo("SUCCEEDED");
		assertThat(testChatProvider.requests(id)).hasSize(2);
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls ORDER BY id", String.class))
			.containsExactly("PROVIDER_ERROR", "SUCCEEDED");
		assertThat(output.getAll()).doesNotContain("job generate failed");
	}

	@ParameterizedTest(name = "종류={0}, 실패={1}")
	@CsvSource({"NEW_COURSE,PROVIDER_REJECTED", "NEW_COURSE,INPUT_TOO_LARGE",
		"NEW_ROADMAP,PROVIDER_REJECTED", "NEW_ROADMAP,INPUT_TOO_LARGE"})
	@DisplayName("거절과 입력 초과는 두 종류 모두 재시도 없이 한 번만 호출하고 복구한다")
	void doesNotRetryPermanentFailures(JobType type, GenerationOutcome.Failure failure) {
		// given
		long id = receive(type);
		testChatProvider.reply(id, TestChatProvider.failure(failure));

		// when
		runner().runOnce();

		// then
		assertFailure(id, failure.name(), 1);
	}

	@ParameterizedTest(name = "종류={0}")
	@EnumSource(value = JobType.class, names = {"NEW_COURSE", "NEW_ROADMAP"})
	@DisplayName("재료 준비의 예외도 밖으로 던지지 않고 작업 실패로 돌린다")
	void returnsFailureWhenPreparingThrows(JobType type, CapturedOutput output) {
		// given
		var generator = generators.stream().filter(candidate -> candidate.type() == type).findFirst().orElseThrow();
		var invalid = new JobSnapshot(1L, 1L, type, null, null, null, 1);

		try (var jobId = MDC.putCloseable(RequestLogFilter.JOB_ID, "1");
			var userId = MDC.putCloseable(RequestLogFilter.USER_ID, "1")) {
			// when
			var outcome = generator.generate(invalid);

			// then
			assertThat(outcome).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
			assertThat(MDC.get(RequestLogFilter.JOB_ID)).isEqualTo("1");
			assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo("1");
			assertThat(output.getAll().lines().filter(line -> line.contains("job generate failed")))
				.singleElement()
				.asString()
				.contains(" ERROR ", "jobId=1", "userId=1");
			assertThat(output.getAll())
				.contains("at com.mogum.generation." + generator.getClass().getSimpleName() + ".generate");
		}
	}

	@Test
	@DisplayName("새 코스는 주제와 목적만 질의하고 가까운 사전 개념을 판정 지시와 함께 넘긴다")
	void suppliesRelatedConceptsBeforeChat() {
		// given
		long id = receive(JobType.NEW_COURSE);
		long userId = owner(id);
		var known = conceptRepository.save(ConceptFixtures.concept(userId, "가까운 개념", "가까운 정의", new float[] {1, 0}));
		new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
			var course = CourseFixtures.single(userId,
				new LearningInput("이전 주제", Level.BEGINNER, null, Purpose.WORK, null, null), "이전 코스");
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
		runner().runOnce();

		// then
		assertThat(status(id)).isEqualTo("SUCCEEDED");
		assertThat(testEmbeddingProvider.requests(id)).singleElement().satisfies(
			request -> assertThat(request.definitions()).containsExactly("LEAKCHECK-topic\nLEAKCHECK-purpose"));
		assertThat(testChatProvider.requests(id)).singleElement().satisfies(request -> {
			var data = new JsonMapper().readTree(request.messages().get(1)
				.text().split("\n", 3)[1]);
			var related = data.path("relatedConcepts");
			assertThat(related).hasSize(2);
			assertThat(related.get(0).path("name").asString()).isEqualTo("가까운 개념");
			assertThat(related.get(0).path("definition").asString()).isEqualTo("가까운 정의");
			assertThat(related.get(0).path("instruction").asString()).isEqualTo("짧게 다룬다.");
			assertThat(related.get(1).path("name").asString()).isEqualTo("먼 개념");
		});
		assertThat(testEmbeddingProvider.outsideTransaction(id)).isTrue();
		assertThat(testChatProvider.outsideTransaction(id)).isTrue();
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.containsExactly("EMBEDDING", "CHAT");
	}

	@ParameterizedTest(name = "임베딩 실패 {index}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("관련 개념 임베딩 실패는 채팅 없이 같은 실패와 호출 기록을 남긴다")
	void failsRelatedEmbeddingBeforeChat(GenerationOutcome.Failure failure) throws InterruptedException {
		// given
		long id = receive(JobType.NEW_COURSE);
		long userId = owner(id);
		conceptRepository.save(ConceptFixtures.concept(userId, "사전 개념", "사전 정의", new float[] {1, 0}));
		var replies = new EmbeddingProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestEmbeddingProvider.failure(failure));
		testEmbeddingProvider.reply(id, replies);

		// when
		runner().runOnce();

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
	}

	@ParameterizedTest(name = "내역={0}")
	@ValueSource(strings = {"{\"shortened\":[\"없는 개념\",\"없는 개념\"],\"revisited\":[\"다시\"]}",
		"null", "[]", "{\"shortened\":[1],\"revisited\":[]}"})
	@DisplayName("새 코스는 반영 내역을 그대로 저장하고 잘못된 내역이면 목록만 비우고 재시도하지 않는다")
	void storesMasteryReportWithoutRetry(String report) {
		// given
		long id = receive(JobType.NEW_COURSE);
		String original = TestChatProvider.defaultText(JobType.NEW_COURSE);
		String reply = original.substring(0, original.lastIndexOf('}')) + ",\"masteryReport\":" + report + "}";
		testChatProvider.reply(id, TestChatProvider.success(reply));

		// when
		boolean processed = runner().runOnce();

		// then
		assertThat(processed).isTrue();
		assertThat(status(id)).isEqualTo("SUCCEEDED");
		assertThat(testChatProvider.requests(id)).hasSize(1);
		String expected = report.startsWith("{\"shortened\":[\"") ? report
			: "{\"shortened\":[],\"revisited\":[]}";
		assertThat(jdbcTemplate.queryForObject("SELECT mastery_report = ?::jsonb FROM courses",
			Boolean.class, expected)).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons", Integer.class)).isEqualTo(2);
	}

	private JobRunner runner() {
		return new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
			workerProperties, generators, renewals);
	}

	private long receive(JobType type) {
		var user = userRepository.save(UserFixtures.approved(
			"me@example.com", 100, testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return jobIntakeService.receive(user.getId(), new JobRequest(type, null,
			new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER,
				"LEAKCHECK-purpose", "LEAKCHECK-memo"),
			null)).id();
	}

	private void assertInput(Map<String, Object> row) {
		assertThat(row).containsEntry("topic", "LEAKCHECK-topic").containsEntry("level", "OTHER")
			.containsEntry("level_other", "LEAKCHECK-level").containsEntry("purpose", "OTHER")
			.containsEntry("purpose_other", "LEAKCHECK-purpose").containsEntry("memo", "LEAKCHECK-memo");
	}

	private void assertFailure(long id, String error, int calls) {
		assertThat(status(id)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT error_type FROM generation_jobs WHERE id = ?", String.class, id)).isEqualTo(error);
		assertThat(testChatProvider.requests(id)).hasSize(calls);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isEqualTo(calls);
		assertThat(balance()).isEqualTo(100);
		assertNoResults();
	}

	private void assertNoResults() {
		for (String table : List.of("courses", "roadmaps", "lessons")) {
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
		}
	}

	private String status(long id) {
		return jdbcTemplate.queryForObject("SELECT status FROM generation_jobs WHERE id = ?", String.class, id);
	}

	private long owner(long id) {
		return jdbcTemplate.queryForObject("SELECT user_id FROM generation_jobs WHERE id = ?", Long.class, id);
	}

	private int balance() {
		return jdbcTemplate.queryForObject("SELECT credit_balance FROM users", Integer.class);
	}
}
