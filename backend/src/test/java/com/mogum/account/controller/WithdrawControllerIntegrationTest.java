package com.mogum.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.auth.AccountPrincipal;
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
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;

import jakarta.servlet.http.Cookie;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class WithdrawControllerIntegrationTest {
	private static final List<String> TABLES = List.of("generation_jobs", "reviews", "card_concepts", "cards",
		"materials", "lessons", "courses", "roadmaps", "concepts", "credit_entries", "users");
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
	TestRequests testRequests;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("탈퇴는 모든 소유 데이터와 두 기기의 세션을 지우고 호출 기록과 다른 사람의 모든 행을 보존한다")
	void withdrawsAllOwnedDataAndSessions(CapturedOutput output) {
		// given
		long otherId = content("other@example.com");
		var otherData = snapshot();
		var otherCalls = jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id");
		long userId = content("LEAKCHECK-withdraw@example.com");
		var sessions = new TestSessions(jdbcIndexedSessionRepository);
		var first = sessions.create(userId);
		var second = sessions.create(userId);
		var otherSession = sessions.create(otherId);
		var client = withCsrf(first);
		var calls = jdbcTemplate.queryForList("SELECT * FROM llm_calls WHERE user_id = ? ORDER BY id", userId);
		calls.forEach(row -> {
			row.put("job_id", null);
			row.put("user_id", null);
		});
		var otherBefore = jdbcTemplate.queryForMap("SELECT * FROM spring_session WHERE session_id = ?",
			sessions.id(otherSession));
		int offset = output.getAll().length();
		// when
		var response = client.post().uri("/api/account/withdraw").exchange().returnResult(String.class);
		// then
		assertThat(response.getStatus().value()).isEqualTo(204);
		assertThat(response.getResponseBody()).isNullOrEmpty();
		assertThat(response.getResponseHeaders().getFirst("Location")).isNull();
		var expired = response.getResponseCookies().getFirst("SESSION");
		assertThat(expired).isNotNull();
		assertThat(expired.getMaxAge()).isZero();
		assertThat(expired.isHttpOnly()).isTrue();
		assertThat(expired.isSecure()).isTrue();
		assertThat(expired.getSameSite()).isEqualTo("Lax");
		assertThat(expired.getPath()).isEqualTo("/");
		assertThat(expired.getDomain()).isNull();
		assertThat(snapshot()).isEqualTo(otherData);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM llm_calls WHERE user_id = ? ORDER BY id", otherId))
			.isEqualTo(otherCalls);
		assertThat(userRepository.findById(userId)).isEmpty();
		assertThat(jdbcTemplate.queryForList("SELECT * FROM llm_calls WHERE user_id IS NULL ORDER BY id"))
			.isEqualTo(calls);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class))
			.isEqualTo(calls.size() * 2);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
			Integer.class, Long.toString(userId))).isZero();
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM spring_session WHERE session_id = ?",
			sessions.id(otherSession))).isEqualTo(otherBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class))
			.isEqualTo(1);
		String requestId = response.getResponseHeaders().getFirst("X-Request-Id");
		await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getAll().substring(offset))
			.contains("path=/api/account/withdraw status=204"));
		var logs = output.getAll().substring(offset);
		assertThat(logs).doesNotContain("LEAKCHECK-withdraw", first.getValue(), second.getValue());
		assertThat(logs.lines().filter(line -> line.contains(" INFO ") || line.contains(" WARN ")
			|| line.contains(" ERROR "))).singleElement().satisfies(line -> assertThat(line)
				.contains("requestId=" + requestId, "access method=POST path=/api/account/withdraw status=204"));
		for (var cookie : List.of(first, second)) {
			testRequests.anonymous().get().uri("/api/me").cookie("SESSION", cookie.getValue()).exchange()
				.expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
			Integer.class, Long.toString(userId))).isZero();
	}

	@ParameterizedTest(name = "상태={0}")
	@ValueSource(strings = {"NOT_APPLIED", "APPLIED", "REJECTED", "ANONYMOUS"})
	@DisplayName("승인 전 탈퇴는 계정 승인 오류이고 로그인하지 않은 탈퇴는 401이며 데이터를 보존한다")
	void rejectsUnapprovedOrAnonymousWithdrawal(String status) {
		// given
		Cookie cookie = null;
		var sessions = new TestSessions(jdbcIndexedSessionRepository);
		if (status.equals("NOT_APPLIED")) {
			cookie = sessions.createNotApplied(new AccountPrincipal.NotApplied("google", "new-sub",
				"new@example.com", "새 이름"));
		} else if (!status.equals("ANONYMOUS")) {
			var user = userRepository.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"),
				UserStatus.valueOf(status)));
			creditEntryRepository.save(CreditEntry.refill(user, 100));
			cookie = sessions.create(user.getId());
		}
		var client = withCsrf(cookie);
		var before = snapshot();
		int sessionsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
		// when
		var response = client.post().uri("/api/account/withdraw").exchange();
		// then
		response.expectStatus().isEqualTo(status.equals("ANONYMOUS") ? 401 : 403).expectBody()
			.jsonPath("$.code").isEqualTo(status.equals("ANONYMOUS") ? "UNAUTHORIZED" : "ACCOUNT_NOT_APPROVED");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class))
			.isEqualTo(sessionsBefore);
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

	private RestTestClient withCsrf(Cookie cookie) {
		var client = testRequests.anonymous();
		if (cookie != null) {
			client = client.mutate().defaultCookie("SESSION", cookie.getValue()).build();
		}
		var token = client.get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
		return client.mutate().defaultCookie("XSRF-TOKEN", token.getValue())
			.defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
	}
}
