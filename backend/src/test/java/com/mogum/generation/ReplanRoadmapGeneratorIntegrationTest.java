package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.plan.CourseOutline;
import com.mogum.generation.plan.RoadmapPlan;
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
class ReplanRoadmapGeneratorIntegrationTest {
	@Autowired
	ReplanRoadmapGenerator replanRoadmapGenerator;
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
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void prepareAdapters() {
		testChatProvider.reset();
		testEmbeddingProvider.reset();
	}

	@ParameterizedTest(name = "이전 코스={0}")
	@ValueSource(ints = {2, 4})
	@DisplayName("로드맵 다시 짜기는 제목과 설명과 전체 코스를 늘리거나 줄이고 이전 계획과 피드백을 넣는다")
	void replacesPlanAndAllCourses(int previousCount, CapturedOutput output) throws InterruptedException {
		// given
		testChatProvider.reset();
		var roadmap = roadmap(previousCount);
		var oldIds = jdbcTemplate.queryForList("SELECT id FROM courses ORDER BY position", Long.class);
		long id = jobIntakeService.receive(roadmap.getUserId(), request(roadmap)).id();
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		String outline = """
			{"title":"새 코스", "description":"새 코스 설명",
			 "concepts":[{"name":"새 개념", "definition":"새 정의"}]}
			""";
		// @checkstyle:on
		testChatProvider.reply(id, TestChatProvider.success("{\"title\":\"새 로드맵\","
			+ "\"description\":\"새 로드맵 설명\",\"courses\":[" + String.join(",", outline, outline, outline) + "]}"));
		testClock.advance(Duration.ofSeconds(1));

		// when
		boolean processed = runOnce();

		// then
		assertThat(processed).isTrue();
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "SUCCEEDED").containsEntry("roadmap_id", roadmap.getId());
		var saved = roadmapRepository.findById(roadmap.getId()).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("새 로드맵");
		assertThat(saved.getDescription()).isEqualTo("새 로드맵 설명");
		assertThat(saved.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getInput().getTopic()).isEqualTo("LEAKCHECK-topic");
		var courses = jdbcTemplate.queryForList("SELECT * FROM courses ORDER BY position");
		assertThat(courses).extracting(row -> row.get("position")).containsExactly(1, 2, 3);
		assertThat(courses).extracting(row -> row.get("id")).doesNotContainAnyElementsOf(oldIds);
		assertThat(courses).allSatisfy(row -> assertThat(row)
			.containsEntry("title", "새 코스").containsEntry("plan_description", "새 코스 설명")
			.containsEntry("status", "NOT_PLANNED").containsEntry("roadmap_id", roadmap.getId())
			.containsEntry("user_id", roadmap.getUserId()).containsEntry("topic", null));
		assertThat(jdbcTemplate.queryForList("SELECT plan_concepts -> 0 ->> 'name' FROM courses", String.class))
			.containsExactly("새 개념", "새 개념", "새 개념");
		assertThat(testChatProvider.outsideTransaction(id)).isTrue();
		assertThat(testEmbeddingProvider.requests(id)).isEmpty();
		String materials = testChatProvider.requests(id).getFirst().messages().get(1).text();
		assertThat(materials).doesNotContain("relatedConcepts");
		assertThat(materials).contains("LEAKCHECK-topic", "LEAKCHECK-purpose", "LEAKCHECK-memo", "LEAKCHECK-level",
			"previousPlan", "이전 로드맵", "이전 로드맵 설명", "사용자가 고친 제목-1", "이전 코스 설명-1",
			"이전 개념-1", "이전 정의-1", "사용자가 고친 제목-" + previousCount, "LEAKCHECK-feedback");
		assertThat(userRepository.findById(roadmap.getUserId()).orElseThrow().getCreditBalance())
			.isEqualTo(100 - creditProperties.replanRoadmap());
		assertThat(output.getAll()).doesNotContain("job generate failed", "LEAKCHECK-", "이전 로드맵", "사용자가 고친 제목");
	}

	@ParameterizedTest(name = "실패={0}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("로드맵 다시 짜기 실패와 버리기는 제목과 설명과 코스를 보존하고 크레딧을 한 번 복구한다")
	void preservesPreviousPlanOnFailureAndDiscard(GenerationOutcome.Failure failure, CapturedOutput output)
		throws InterruptedException {
		// given
		testChatProvider.reset();
		var roadmap = roadmap(3);
		var before = content();
		long id = jobIntakeService.receive(roadmap.getUserId(), request(roadmap)).id();
		var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
		Arrays.fill(replies, TestChatProvider.failure(failure));
		testChatProvider.reply(id, replies);
		runOnce();
		assertThat(content()).isEqualTo(before);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
			.containsEntry("status", "FAILED").containsEntry("error_type", failure.name());

		// when
		jobIntakeService.discard(roadmap.getUserId(), id);

		// then
		assertThat(output.getAll()).doesNotContain("job generate failed");
		assertThat(content()).isEqualTo(before);
		assertThat(userRepository.findById(roadmap.getUserId()).orElseThrow().getCreditBalance()).isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
			Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
	}

	@Test
	@DisplayName("없는 로드맵 재료는 예외 대신 실패로 반환한다")
	void returnsFailureWhenMaterialsAreMissing(CapturedOutput output) {
		// given
		var job = new JobSnapshot(1L, 1L, JobType.REPLAN_ROADMAP, 99999L, null, null, 1);

		// when
		var outcome = replanRoadmapGenerator.generate(job);

		// then
		assertThat(outcome).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
		assertThat(output.getAll().lines().filter(line -> line.contains("job generate failed")))
			.singleElement()
			.asString()
			.contains(" ERROR ");
		assertThat(output.getAll()).contains("at com.mogum.generation.ReplanRoadmapGenerator.generate");
	}

	@ParameterizedTest(name = "위반={0}")
	@ValueSource(strings = {"roadmap", "course"})
	@DisplayName("검토 중이 아닌 로드맵이나 짜기 전이 아닌 코스가 있으면 불변식 위반으로 저장을 거절한다")
	void rejectsSaveWhenInvariantIsBroken(String target) {
		// given
		var roadmap = roadmap(2);
		if (target.equals("roadmap")) {
			new TransactionTemplate(platformTransactionManager)
				.executeWithoutResult(tx -> roadmapRepository.findById(roadmap.getId()).orElseThrow().start());
		} else {
			jdbcTemplate.update("UPDATE courses SET status = 'REVIEWING' WHERE position = 1");
		}
		var before = content();
		var job = new JobSnapshot(1L, roadmap.getUserId(), JobType.REPLAN_ROADMAP,
			roadmap.getId(), null, null, 1);
		var result = new ReplanRoadmapGenerator.Result(new RoadmapPlan("새 제목", "새 설명",
			List.of(new CourseOutline("코스", "설명", List.of(new PlannedConcept("개념", "정의"))))));

		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager)
			.execute(tx -> replanRoadmapGenerator.save(job, result))).isInstanceOf(IllegalStateException.class);
		assertThat(content()).isEqualTo(before);
	}

	@Test
	@DisplayName("코스 삭제 뒤 새 코스 저장이 실패하면 제목과 설명과 삭제를 모두 롤백한다")
	void rollsBackReplacementWhenInsertFails() {
		// given
		var roadmap = roadmap(2);
		var before = content();
		var job = new JobSnapshot(1L, roadmap.getUserId(), JobType.REPLAN_ROADMAP,
			roadmap.getId(), null, null, 1);
		var result = new ReplanRoadmapGenerator.Result(new RoadmapPlan("새 제목", "새 설명",
			List.of(new CourseOutline("가".repeat(101), "설명", List.of(new PlannedConcept("개념", "정의"))))));

		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager)
			.execute(tx -> replanRoadmapGenerator.save(job, result)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(content()).isEqualTo(before);
	}

	@Test
	@DisplayName("로드맵 계획은 주제와 목적 질의로 관련 개념과 판정을 준비한 뒤 트랜잭션 밖에서 채팅한다")
	void suppliesRelatedConceptsBeforeChat(CapturedOutput output) throws InterruptedException {
		// given
		var roadmap = roadmap(2);
		long userId = roadmap.getUserId();
		long id = jobIntakeService.receive(userId, request(roadmap)).id();
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
		var roadmap = roadmap(2);
		long userId = roadmap.getUserId();
		long id = jobIntakeService.receive(userId, request(roadmap)).id();
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

	private List<?> content() {
		return List.of("roadmaps", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
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

	private Roadmap roadmap(int count) {
		var user = userRepository.save(UserFixtures.approved("me@example.com", 100,
			testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		var input = new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER,
			"LEAKCHECK-purpose", "LEAKCHECK-memo");
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId(), input,
			"이전 로드맵", "이전 로드맵 설명"));
		for (int i = 1; i <= count; i++) {
			courseRepository.save(CourseFixtures.inRoadmap(roadmap, i, "사용자가 고친 제목-" + i,
				"이전 코스 설명-" + i, List.of(new PlannedConcept("이전 개념-" + i, "이전 정의-" + i))));
		}
		return roadmap;
	}

	private JobRequest request(Roadmap roadmap) {
		return new JobRequest(JobType.REPLAN_ROADMAP, roadmap.getId(), null, "LEAKCHECK-feedback");
	}
}
