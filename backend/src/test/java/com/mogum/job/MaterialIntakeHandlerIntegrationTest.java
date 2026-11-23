package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.CreditProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Material;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobResult;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class MaterialIntakeHandlerIntegrationTest {
	@Autowired
	TestRequests testRequests;
	@Autowired
	TestClock testClock;
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
	MaterialRepository materialRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	CreditProperties creditProperties;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "상태={0}, 순서={1}, 앞 완료={2}, 허용={3}")
	@CsvSource({"LEARNING,1,false,true", "LEARNING,3,true,true", "COMPLETED,3,true,true",
		"REVIEWING,1,false,false", "NOT_PLANNED,1,false,false", "LEARNING,3,false,false"})
	@DisplayName("HTTP 받기는 학습 가능한 코스와 앞 강의 전체 완료를 요구하고 거절하면 DB를 보존한다")
	void checksLessonAvailabilityOverHttp(CourseStatus status, int position, boolean previousCompleted,
		boolean accepted) {
		// given
		long userId = user("me@example.com");
		long lessonId = lesson(userId, status, position, previousCompleted);
		var before = state();
		var client = testRequests.asUser(userId);
		// when
		var response = client.post().uri("/api/jobs").body(Map.of("type", "MATERIAL", "lessonId", lessonId)).exchange();
		// then
		if (accepted) {
			response.expectStatus().isOk().expectBody().jsonPath("$.data.type").isEqualTo("MATERIAL")
				.jsonPath("$.data.status").isEqualTo("QUEUED");
			assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance())
				.isEqualTo(100 - creditProperties.material());
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
		} else {
			response.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("LESSON_NOT_OPEN")
				.jsonPath("$.message").isEqualTo("코스를 시작하고 앞 강의를 학습 완료하면 열 수 있어요");
			assertThat(state()).isEqualTo(before);
		}
	}

	@Test
	@DisplayName("남의 강의는 열 수 있는지 확인하기 전에 HTTP 404로 숨기고 DB를 보존한다")
	void hidesForeignLesson() {
		// given
		long owner = user("owner@example.com");
		long lessonId = lesson(owner, CourseStatus.REVIEWING, 1, false);
		long other = user("other@example.com");
		var before = state();
		var client = testRequests.asUser(other);
		// when
		var response = client.post().uri("/api/jobs").body(Map.of("type", "MATERIAL", "lessonId", lessonId)).exchange();
		// then
		response.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
		assertThat(state()).isEqualTo(before);
	}

	@Test
	@DisplayName("이미 자료가 있는 성공한 강의를 다시 받으면 같은 작업을 돌려주고 크레딧을 쓰지 않는다")
	void returnsSucceededJobDespiteExistingMaterial() {
		// given
		long userId = user("me@example.com");
		long lessonId = lesson(userId, CourseStatus.LEARNING, 1, false);
		var job = generationJobRepository.save(GenerationJobFixtures.material(userId, lessonId));
		materialRepository.save(Material.write(lessonRepository.findById(lessonId).orElseThrow(), "이미 있는 본문"));
		GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), null, null, lessonId, testClock.instant());
		var before = state();
		var client = testRequests.asUser(userId);
		// when
		var response = client.post().uri("/api/jobs").body(Map.of("type", "MATERIAL", "lessonId", lessonId)).exchange();
		// then
		response.expectStatus().isOk().expectBody().jsonPath("$.data.id").isEqualTo(job.getId().intValue())
			.jsonPath("$.data.status").isEqualTo("SUCCEEDED").jsonPath("$.data.result.type").isEqualTo("LESSON");
		assertThat(state()).isEqualTo(before);
	}

	@Test
	@DisplayName("동시에 보낸 두 HTTP 받기는 같은 강의 작업과 크레딧 사용 하나만 남긴다")
	void deduplicatesConcurrentHttpRequests() throws Exception {
		// given
		long userId = user("me@example.com");
		long lessonId = lesson(userId, CourseStatus.LEARNING, 1, false);
		var firstClient = testRequests.asUser(userId);
		var secondClient = testRequests.asUser(userId);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return firstClient.post().uri("/api/jobs").body(Map.of("type", "MATERIAL", "lessonId", lessonId))
					.exchange();
			});
			var second = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return secondClient.post().uri("/api/jobs").body(Map.of("type", "MATERIAL", "lessonId", lessonId))
					.exchange();
			});
			// when
			start.countDown();
			var firstResponse = first.get(10, TimeUnit.SECONDS);
			var secondResponse = second.get(10, TimeUnit.SECONDS);
			// then
			long id = jdbcTemplate.queryForObject("SELECT id FROM generation_jobs", Long.class);
			firstResponse.expectStatus().isOk().expectBody().jsonPath("$.data.id").isEqualTo((int)id);
			secondResponse.expectStatus().isOk().expectBody().jsonPath("$.data.id").isEqualTo((int)id);
			assertConsumedOnce(userId);
		}
	}

	@Test
	@DisplayName("사용자 행 잠금에 경합한 두 받기도 하나의 작업과 크레딧 사용만 커밋한다")
	void serializesConcurrentDatabaseIntake() throws Exception {
		// given
		long userId = user("me@example.com");
		long lessonId = lesson(userId, CourseStatus.LEARNING, 1, false);
		var entered = new CountDownLatch(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			// when
			var futures = new TransactionTemplate(platformTransactionManager).execute(status -> {
				jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
				Callable<JobResult> receive = () -> {
					entered.countDown();
					return jobIntakeService.receive(userId, new JobRequest(JobType.MATERIAL, lessonId, null, null));
				};
				var first = executor.submit(receive);
				var second = executor.submit(receive);
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return List.of(first, second);
			});
			var first = futures.get(0).get(10, TimeUnit.SECONDS);
			var second = futures.get(1).get(10, TimeUnit.SECONDS);
			// then
			assertThat(first.id()).isEqualTo(second.id());
			assertConsumedOnce(userId);
		}
	}

	private void assertConsumedOnce(long userId) {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
		assertThat(
			jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'CONSUME'", Integer.class))
			.isEqualTo(1);
		assertThat(userRepository.findById(userId).orElseThrow().getCreditBalance())
			.isEqualTo(100 - creditProperties.material());
	}

	private long user(String email) {
		var user = userRepository
			.save(UserFixtures.approved(email, 100, testClock.instant().plus(Duration.ofDays(30))));
		creditEntryRepository.save(CreditEntry.refill(user, 100));
		return user.getId();
	}

	private long lesson(long userId, CourseStatus status, int position, boolean previousCompleted) {
		var roadmap = status == CourseStatus.NOT_PLANNED ? roadmapRepository.save(RoadmapFixtures.roadmap(userId))
			: null;
		var course = courseRepository.save(CourseFixtures.withStatus(roadmap == null ? CourseFixtures.single(userId)
			: CourseFixtures.inRoadmap(roadmap, 1, "코스", "설명", List.of()), status));
		for (int index = 1; index < position; index++) {
			var previous = LessonFixtures.lesson(course, index);
			// 바로 앞만 완료여도 더 앞이 미완료라면 거절한다.
			if (previousCompleted || index == position - 1) {
				LessonFixtures.withCompletedAt(previous, testClock.instant());
			}
			lessonRepository.save(previous);
		}
		return lessonRepository.save(LessonFixtures.lesson(course, position)).getId();
	}

	private List<List<Map<String, Object>>> state() {
		return List.of("users", "courses", "lessons", "materials", "generation_jobs", "credit_entries").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
