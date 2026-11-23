package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.mogum.card.service.LessonCardWriter;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.config.CreditProperties;
import com.mogum.config.LlmProperties;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.material.MaterialFormat;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobReadService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestChatProvider;
import com.mogum.support.IntegrationTestConfiguration.TestEmbeddingProvider;
import com.mogum.support.TestClock;
import com.mogum.worker.JobRunner;

import jakarta.persistence.EntityManager;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MaterialGeneratorIntegrationTest {
	@Autowired
	MaterialGenerator materialGenerator;
	@Autowired
	MaterialReader materialReader;
	@Autowired
	ChatCall chatCall;
	@Autowired
	LlmCallWriter llmCallWriter;
	@Autowired
	MaterialRepository materialRepository;
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
	LlmProperties llmProperties;
	@Autowired
	TestClock testClock;
	@Autowired
	List<JobGenerator> generators;
	@Autowired
	TestChatProvider testChatProvider;
	@Autowired
	TestEmbeddingProvider testEmbeddingProvider;
	@Autowired
	JobIntakeService jobIntakeService;
	@Autowired
	JobReadService jobReadService;
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
	LessonCardWriter lessonCardWriter;
	@Autowired
	JdbcTemplate jdbcTemplate;

	@ParameterizedTest(name = "로드맵={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("운영 워커가 자료와 순서 있는 카드를 함께 저장하고 허용된 재료로 채팅과 임베딩을 한 번씩 부른다")
	void generatesAndSavesMaterial(boolean roadmap, CapturedOutput output) throws InterruptedException {
		// given
		var lesson = lesson(roadmap);
		long userId = owner();
		var before = jdbcTemplate.queryForMap("SELECT * FROM courses");
		var beforeLessons = jdbcTemplate.queryForList("SELECT * FROM lessons ORDER BY position");
		long id = receive(lesson);
		// when
		boolean processed = runOnce();
		// then
		assertThat(processed).isTrue();
		assertThat(materialGenerator.type()).isEqualTo(JobType.MATERIAL);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "SUCCEEDED").containsEntry("lesson_id", lesson.getId());
		assertThat(jobReadService.read(userId, id).result().type()).isEqualTo("LESSON");
		assertThat(jobReadService.read(userId, id).result().id()).isEqualTo(lesson.getId());
		assertThat(jdbcTemplate.queryForObject("SELECT body FROM materials", String.class)).contains("이번 강의에서 배울 것");
		var cards = jdbcTemplate.queryForList("SELECT * FROM cards ORDER BY position");
		assertThat(cards).extracting(row -> row.get("position")).containsExactly(1, 2, 3);
		assertThat(cards).extracting(row -> row.get("question")).containsExactly("변수란?", "함수란?", "변수와 함수의 관계는?");
		assertThat(cards).allSatisfy(row -> {
			assertThat(row).containsEntry("status", "ACTIVE").containsEntry("lesson_id", lesson.getId())
				.containsEntry("user_id", userId);
			assertThat(row.get("due_at")).isNull();
		});
		assertThat(count("concepts")).isEqualTo(2);
		assertThat(count("card_concepts")).isEqualTo(4);
		assertThat(jdbcTemplate.queryForList("""
			SELECT c.position || ':' || concept.name FROM card_concepts link
			JOIN cards c ON c.id = link.card_id JOIN concepts concept ON concept.id = link.concept_id
			ORDER BY c.position, concept.id
			""", String.class)).containsExactly("1:변수", "2:함수", "3:변수", "3:함수");
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM courses")).isEqualTo(before);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM lessons ORDER BY position")).isEqualTo(beforeLessons);
		assertThat(
			jdbcTemplate.queryForList("SELECT completed_at FROM lessons WHERE position >= 2", java.sql.Timestamp.class))
			.containsOnlyNulls();
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.containsExactly("CHAT", "EMBEDDING");
		assertThat(jdbcTemplate.queryForList("SELECT * FROM llm_calls"))
			.allSatisfy(row -> assertThat(row).containsEntry("job_id", id).containsEntry("user_id", userId)
				.containsEntry("job_type", "MATERIAL").containsEntry("outcome", "SUCCEEDED"));
		assertThat(testChatProvider.outsideTransaction(id)).isTrue();
		assertThat(testEmbeddingProvider.outsideTransaction(id)).isTrue();
		assertThat(testChatProvider.requests(id)).singleElement().satisfies(request -> {
			assertThat(request.settings()).isEqualTo(llmProperties.material());
			assertThat(request.messages().get(1).text()).contains("코스 제목", "LEAKCHECK-topic", "LEAKCHECK-memo",
				"이번 제목", "이번 설명", "이번 개념", "이번 정의", "前 제목", "後 제목", "\"current\":true",
				"예제 · 실습 · 현업에서 주의할 점")
				.doesNotContain("LEAKCHECK-level", "LEAKCHECK-feedback", "다른개념", "다른정의", "level", "feedback", "mastery",
					"계획정의");
		});
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance())
			.isEqualTo(100 - creditProperties.material());
		assertThat(output.getAll()).doesNotContain("job generate failed", "LEAKCHECK-", "이번 정의");
		assertThat(testEmbeddingProvider.requests(id)).singleElement().satisfies(request -> {
			assertThat(request.definitions()).containsExactly("값을 담는 이름", "동작의 묶음");
			assertThat(request.settings().model()).isEqualTo(llmProperties.embedding().model());
			assertThat(request.settings().timeout()).isEqualTo(llmProperties.embedding().timeout());
		});
	}

	@Test
	@DisplayName("잘린 카드 JSON은 이어 쓰기 한 번 뒤 해석하고 임베딩은 한 번만 한다")
	void continuesTruncatedOutput() throws InterruptedException {
		// given
		var lesson = lesson(false);
		long id = receive(lesson);
		String text = TestChatProvider.defaultText(JobType.MATERIAL);
		int split = text.indexOf("definition") + 3;
		testChatProvider.reply(id,
			new ChatProvider.Reply(text.substring(0, split), LlmCallOutcome.TRUNCATED, null, null),
			TestChatProvider.success(text.substring(split)));
		// when
		runOnce();
		// then
		assertThat(count("materials")).isEqualTo(1);
		assertThat(testChatProvider.requests(id)).hasSize(2);
		assertThat(testChatProvider.requests(id).get(1).messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo(ChatProvider.Role.ASSISTANT);
			assertThat(message.text()).isEqualTo(text.substring(0, split));
		});
		assertThat(testEmbeddingProvider.requests(id)).hasSize(1);
		assertThat(jdbcTemplate.queryForList("SELECT call_type FROM llm_calls ORDER BY id", String.class))
			.containsExactly("CHAT", "CONTINUATION", "EMBEDDING");
	}

	@Test
	@DisplayName("형식 불합격이 계속되면 임베딩과 결과 없이 실패하고 크레딧을 복구한다")
	void failsInvalidOutput(CapturedOutput output) throws InterruptedException {
		// given
		var lesson = lesson(false);
		long id = receive(lesson);
		var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestChatProvider.success("LEAKCHECK-invalid-output"));
		testChatProvider.reply(id, replies);
		// when
		runOnce();
		// then
		assertFailed(id, GenerationOutcome.Failure.INVALID_OUTPUT);
		assertThat(testEmbeddingProvider.requests(id)).isEmpty();
		assertThat(testChatProvider.requests(id)).hasSize(workerProperties.maxRetries() + 1);
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls ORDER BY id", String.class))
			.hasSize(workerProperties.maxRetries() + 1).containsOnly("INVALID_OUTPUT");
		assertThat(output.getAll()).doesNotContain("LEAKCHECK-invalid-output", "job generate failed");
	}

	@ParameterizedTest(name = "실패={0}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("임베딩 실패의 갈래를 보존하고 자료와 카드와 개념 없이 크레딧을 복구한다")
	void failsEmbeddingAtomically(GenerationOutcome.Failure failure) throws InterruptedException {
		// given
		var lesson = lesson(false);
		long id = receive(lesson);
		var replies = new EmbeddingProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestEmbeddingProvider.failure(failure));
		testEmbeddingProvider.reply(id, replies);
		// when
		runOnce();
		// then
		assertFailed(id, failure);
		int attempts = failure == GenerationOutcome.Failure.INVALID_OUTPUT
			|| failure == GenerationOutcome.Failure.PROVIDER_UNAVAILABLE
				? workerProperties.maxRetries() + 1 : 1;
		assertThat(testEmbeddingProvider.requests(id)).hasSize(attempts);
		assertThat(testChatProvider.requests(id)).hasSize(attempts);
	}

	@ParameterizedTest(name = "실패={0}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("채팅 실패의 갈래를 보존하고 임베딩이나 빈 결과를 쓰지 않는다")
	void failsChatAtomically(GenerationOutcome.Failure failure) throws InterruptedException {
		// given
		var lesson = lesson(false);
		long id = receive(lesson);
		var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestChatProvider.failure(failure));
		testChatProvider.reply(id, replies);
		// when
		runOnce();
		// then
		assertFailed(id, failure);
		assertThat(testEmbeddingProvider.requests(id)).isEmpty();
	}

	@Test
	@DisplayName("앞 강의에 저장한 개념과 같은 벡터이면 새 개념을 만들지 않고 연결한다")
	void reusesPreviousLessonConcepts() throws InterruptedException {
		// given
		var target = lesson(false);
		long userId = owner();
		long previous = lessonRepository.findByCourseIdOrderByPositionAsc(target.getCourse().getId()).getFirst()
			.getId();
		testEmbeddingProvider.vector("값을 담는 이름", List.of(1f, 0f));
		testEmbeddingProvider.vector("동작의 묶음", List.of(0f, 1f));
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(status -> lessonCardWriter.writeLessonCards(userId, previous,
				List.of(new NewCard("옛 질문", "옛 답", List.of(new NewConcept("기존 변수", "기존 정의", new float[] {1, 0}),
					new NewConcept("기존 함수", "기존 정의 둘", new float[] {0, 1}))))));
		long id = receive(target);
		// when
		runOnce();
		// then
		assertThat(count("concepts")).isEqualTo(2);
		assertThat(jdbcTemplate.queryForList("SELECT name FROM concepts ORDER BY id", String.class))
			.containsExactly("기존 변수", "기존 함수");
		assertThat(count("card_concepts")).isEqualTo(6);
		assertThat(testEmbeddingProvider.requests(id)).hasSize(1);
	}

	@Test
	@DisplayName("재료 읽기의 예상 밖 예외는 안전한 생성 실패 로그와 일시적 실패로 바꾼다")
	void reportsUnexpectedFailure(CapturedOutput output) {
		// given
		var job = new JobSnapshot(1, 1, JobType.MATERIAL, 999999L, null, null, 1);
		// when
		var result = materialGenerator.generate(job);
		// then
		assertThat(result).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		assertThat(output.getAll()).contains("job generate failed",
			"at com.mogum.generation.MaterialGenerator.generate");
	}

	@Test
	@DisplayName("임베딩의 예상 밖 예외는 호출 기록을 남기고 원문 없는 생성 실패 로그로 전달한다")
	void reportsUnexpectedEmbeddingFailure(CapturedOutput output) {
		// given
		var target = lesson(false);
		long id = receive(target);
		String marker = "LEAKCHECK-embedding-exception";
		var embeddingCall = new EmbeddingCall(request -> {
			throw new IllegalStateException(marker);
		}, llmCallWriter, llmProperties.prices());
		var generator = new MaterialGenerator(chatCall, llmProperties.material(), embeddingCall,
			new EmbeddingSettings(llmProperties.embedding().model(), llmProperties.embedding().timeout()),
			new MaterialFormat(), materialReader, lessonRepository, materialRepository, lessonCardWriter);
		// when
		var result = generator.generate(new JobSnapshot(id, owner(), JobType.MATERIAL, target.getId(), null, null, 1));
		// then
		assertThat(result).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		assertThat(output.getAll())
			.contains("job generate failed", "at com.mogum.generation.MaterialGenerator.generate")
			.doesNotContain(marker);
		assertThat(jdbcTemplate.queryForList("SELECT outcome FROM llm_calls ORDER BY id", String.class))
			.containsExactly("SUCCEEDED", "PROVIDER_ERROR");
		assertThat(count("materials")).isZero();
		assertThat(count("cards")).isZero();
		assertThat(count("concepts")).isZero();
	}

	@ParameterizedTest(name = "소유권 상실={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("저장 뒤 예외는 결과 전체를 롤백하고 소유권을 잃은 워커는 저장을 시작하지 않는다")
	void preservesAtomicSaveAndOwnership(boolean lost, CapturedOutput output) throws InterruptedException {
		// given
		var target = lesson(false);
		long id = receive(target);
		var generator = new JobGenerator() {
			@Override
			public JobType type() {
				return JobType.MATERIAL;
			}

			@Override
			public GenerationOutcome generate(JobSnapshot job) {
				var outcome = materialGenerator.generate(job);
				if (lost) {
					jdbcTemplate.update(
						"UPDATE generation_jobs SET claim_count = claim_count + 1, updated_at = ? WHERE id = ?",
						java.sql.Timestamp.from(testClock.instant()), job.id());
				}
				return outcome;
			}

			@Override
			public ResultRef save(JobSnapshot job, GeneratedResult result) {
				materialGenerator.save(job, result);
				throw new IllegalStateException("LEAKCHECK-save-exception");
			}
		};
		// when
		runOnce(List.of(generator));
		// then
		for (String table : List.of("materials", "cards", "concepts", "card_concepts")) {
			assertThat(count(table)).isZero();
		}
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_jobs WHERE id = ?", String.class, id))
			.isEqualTo("RUNNING");
		assertThat(count("llm_calls")).isEqualTo(2);
		assertThat(output.getAll()).doesNotContain("LEAKCHECK-save-exception");
		if (lost) {
			assertThat(output.getAll()).contains("job lease lost").doesNotContain("job save failed");
		} else {
			assertThat(output.getAll()).contains("job save failed");
		}
	}

	private Lesson lesson(boolean inRoadmap) {
		testChatProvider.reset();
		testEmbeddingProvider.reset();
		var user = userRepository
			.save(UserFixtures.approved("me@example.com", 100, testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var input = new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.WORK, null,
			"LEAKCHECK-memo");
		var roadmap = inRoadmap ? roadmapRepository.save(RoadmapFixtures.roadmap(user.getId(), input, "로드맵", "설명"))
			: null;
		var course = courseRepository.save(CourseFixtures.withStatus(inRoadmap
			? CourseFixtures.inRoadmap(roadmap, 1, "코스 제목", "계획 설명", List.of(new PlannedConcept("계획개념", "계획정의")))
			: CourseFixtures.single(user.getId(), input, "코스 제목"), CourseStatus.LEARNING));
		lessonRepository.save(LessonFixtures
			.withCompletedAt(LessonFixtures.lesson(course, 1, "前 제목", "前 설명", List.of()), testClock.instant()));
		var target = lessonRepository
			.save(LessonFixtures.lesson(course, 2, "이번 제목", "이번 설명", List.of(new PlannedConcept("이번 개념", "이번 정의"))));
		lessonRepository
			.save(LessonFixtures.lesson(course, 3, "後 제목", "後 설명", List.of(new PlannedConcept("다른개념", "다른정의"))));
		return target;
	}

	private long owner() {
		return jdbcTemplate.queryForObject("SELECT id FROM users", Long.class);
	}

	private long receive(Lesson lesson) {
		return jobIntakeService.receive(owner(), new JobRequest(JobType.MATERIAL, lesson.getId(), null, null)).id();
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private void assertFailed(long id, GenerationOutcome.Failure failure) {
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "FAILED").containsEntry("error_type", failure.name());
		for (String table : List.of("materials", "cards", "concepts", "card_concepts")) {
			assertThat(count(table)).isZero();
		}
		assertThat(userRepository.findById(owner()).orElseThrow().getCreditBalance()).isEqualTo(100);
		assertThat(
			jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'", Integer.class))
			.isEqualTo(1);
	}

	private boolean runOnce() throws InterruptedException {
		return runOnce(generators);
	}

	private boolean runOnce(List<JobGenerator> jobGenerators) throws InterruptedException {
		var scheduler = Executors.newSingleThreadScheduledExecutor();
		try {
			return new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock, workerProperties,
				jobGenerators, scheduler).runOnce();
		} finally {
			scheduler.shutdownNow();
			assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}
}
