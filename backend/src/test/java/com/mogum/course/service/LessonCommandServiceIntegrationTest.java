package com.mogum.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.repository.CardRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.MaterialFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class LessonCommandServiceIntegrationTest {
	@Autowired
	LessonCommandService lessonCommandService;
	@Autowired
	LessonReadService lessonReadService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@Test
	@DisplayName("중간 강의 완료는 그 강의 카드만 공개하고 FSRS 상태와 다른 강의는 유지한다")
	void completesMiddleLessonAndActivatesOnlyItsCards() {
		// given
		long userId = user();
		var course = course(userId);
		var first = materialLesson(course, 1);
		var second = lessonRepository.save(LessonFixtures.lesson(course, 2));
		var card = cardRepository.save(CardFixtures.card(userId, first.getId(), 1, "질문", "답"));
		var other = cardRepository.save(CardFixtures.card(userId, second.getId(), 1, "다른 질문", "답"));
		var otherBefore = cardRepository.findById(other.getId()).orElseThrow();
		var courseBefore = courseRepository.findById(course.getId()).orElseThrow();
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = lessonCommandService.complete(userId, first.getId());
		// then
		assertThat(result.courseId()).isEqualTo(course.getId());
		assertThat(result.courseCompleted()).isFalse();
		assertThat(lessonRepository.findById(first.getId()).orElseThrow().getCompletedAt())
			.isEqualTo(testClock.instant());
		assertThat(lessonRepository.findById(second.getId()).orElseThrow().getCompletedAt()).isNull();
		var saved = cardRepository.findById(card.getId()).orElseThrow();
		assertThat(saved.getDueAt()).isEqualTo(testClock.instant());
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant()).isAfter(card.getUpdatedAt());
		assertThat(saved).usingRecursiveComparison().ignoringFields("dueAt", "updatedAt").isEqualTo(card);
		assertThat(cardRepository.findById(other.getId()).orElseThrow()).usingRecursiveComparison()
			.isEqualTo(otherBefore);
		assertThat(courseRepository.findById(course.getId()).orElseThrow()).usingRecursiveComparison()
			.isEqualTo(courseBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isZero();
	}

	@Test
	@DisplayName("마지막 강의 완료는 같은 시각에 코스를 다 마침으로 바꾸고 축하 화면 응답을 준다")
	void completesCourseThroughHttp() {
		// given
		long userId = user();
		var course = course(userId);
		var first = materialLesson(course, 1);
		var last = materialLesson(course, 2);
		lessonCommandService.complete(userId, first.getId());
		var client = testRequests.asUser(userId);
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = client.post().uri("/api/lessons/" + last.getId() + "/complete").exchange();
		// then
		result.expectStatus().isOk().expectBody().jsonPath("$.data.courseId").isEqualTo(course.getId())
			.jsonPath("$.data.courseCompleted").isEqualTo(true);
		var saved = courseRepository.findById(course.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(CourseStatus.COMPLETED);
		assertThat(saved.getCompletedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(saved.getLastLessonOpenedAt()).isNull();
	}

	@ParameterizedTest(name = "마지막 코스={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("로드맵은 뒤 순서 코스가 없는 코스의 마지막 강의 완료에서만 다 마침이 된다")
	void completesOnlyLastRoadmapCourse(boolean lastCourse) {
		// given
		long userId = user();
		var roadmap = roadmapRepository
			.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		var first = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 2, "앞 코스", "설명", List.of()),
			lastCourse ? CourseStatus.COMPLETED : CourseStatus.LEARNING));
		var last = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 7, "뒤 코스", "설명", List.of()),
			lastCourse ? CourseStatus.LEARNING : CourseStatus.NOT_PLANNED));
		var target = lastCourse ? last : first;
		var previous = lessonRepository
			.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(target, 1), testClock.instant()));
		var lesson = materialLesson(target, 2);
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = lessonCommandService.complete(userId, lesson.getId());
		// then
		assertThat(result.courseCompleted()).isTrue();
		assertThat(courseRepository.findById(target.getId()).orElseThrow().getStatus())
			.isEqualTo(CourseStatus.COMPLETED);
		assertThat(courseRepository.findById(target.getId()).orElseThrow().getCompletedAt())
			.isEqualTo(testClock.instant());
		var saved = roadmapRepository.findById(roadmap.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(lastCourse ? RoadmapStatus.COMPLETED : RoadmapStatus.IN_PROGRESS);
		assertThat(saved.getUpdatedAt()).isEqualTo(lastCourse ? testClock.instant() : roadmap.getUpdatedAt());
		assertThat(lessonRepository.findById(previous.getId()).orElseThrow().getCompletedAt())
			.isEqualTo(previous.getCompletedAt());
	}

	@Test
	@DisplayName("완료를 다시 보내면 강의와 코스 시각과 복습 후 카드 및 복습 기록을 바꾸지 않는다")
	void leavesRepeatedCompletionUntouched() {
		// given
		long userId = user();
		var course = course(userId);
		var lesson = materialLesson(course, 1);
		var card = cardRepository.save(CardFixtures.card(userId, lesson.getId(), 1, "질문", "답"));
		lessonCommandService.complete(userId, lesson.getId());
		testClock.advance(Duration.ofSeconds(10));
		jdbcTemplate.update("UPDATE cards SET due_at = ?, fsrs_state = 'REVIEW', fsrs_step = NULL, "
			+ "stability = 20, difficulty = 4, last_reviewed_at = ?, updated_at = ? WHERE id = ?",
			Timestamp.from(testClock.instant().plusSeconds(86400)), Timestamp.from(testClock.instant()),
			Timestamp.from(testClock.instant()), card.getId());
		jdbcTemplate.update("INSERT INTO reviews(card_id, rating, created_at, updated_at) VALUES (?, 'GOOD', ?, ?)",
			card.getId(), Timestamp.from(testClock.instant()), Timestamp.from(testClock.instant()));
		var before = state();
		testClock.advance(Duration.ofSeconds(10));
		// when
		var result = lessonCommandService.complete(userId, lesson.getId());
		// then
		assertThat(result.courseId()).isEqualTo(course.getId());
		assertThat(result.courseCompleted()).isFalse();
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "경우={0}")
	@ValueSource(strings = {"FOREIGN", "MISSING", "REVIEWING", "PREVIOUS", "NO_MATERIAL"})
	@DisplayName("소유권과 열기 조건 및 자료 조건을 먼저 확인하고 거절하면 아무것도 바꾸지 않는다")
	void rejectsInvalidCompletion(String scenario) {
		// given
		long userId = user();
		long owner = scenario.equals("FOREIGN") ? userRepository.save(UserFixtures.user("other@example.com")).getId()
			: userId;
		var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(owner),
			scenario.equals("REVIEWING") ? CourseStatus.REVIEWING : CourseStatus.LEARNING));
		var first = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var target = scenario.equals("PREVIOUS") ? materialLesson(course, 2) : first;
		long id = scenario.equals("MISSING") ? 999999L : target.getId();
		var client = testRequests.asUser(userId);
		var before = state();
		// when
		var result = client.post().uri("/api/lessons/" + id + "/complete").exchange();
		// then
		boolean hidden = scenario.equals("FOREIGN") || scenario.equals("MISSING");
		result.expectStatus().isEqualTo(hidden ? 404 : 409).expectBody().jsonPath("$.code")
			.isEqualTo(
				hidden ? "NOT_FOUND" : scenario.equals("NO_MATERIAL") ? "LESSON_MATERIAL_REQUIRED" : "LESSON_NOT_OPEN");
		assertThat(state()).isEqualTo(before);
	}

	@ParameterizedTest(name = "겹친 동작={0}")
	@ValueSource(strings = {"COMPLETE", "READ"})
	@DisplayName("겹친 완료와 읽기는 DB 코스 잠금에서 기다리고 완료 시각과 카드 일정은 한 번만 적힌다")
	void serializesConcurrentCompletionAndRead(String action) throws Exception {
		// given
		long userId = user();
		var course = course(userId);
		var lesson = materialLesson(course, 1);
		var card = cardRepository.save(CardFixtures.card(userId, lesson.getId(), 1, "질문", "답"));
		var entered = new CountDownLatch(1);
		var firstTime = testClock.instant();
		try (var executor = Executors.newSingleThreadExecutor()) {
			// when
			var pending = new TransactionTemplate(platformTransactionManager).execute(tx -> {
				var firstResult = lessonCommandService.complete(userId, lesson.getId());
				assertThat(firstResult.courseCompleted()).isTrue();
				var second = executor.submit(() -> {
					entered.countDown();
					if (action.equals("READ")) {
						return lessonReadService.read(userId, lesson.getId()).completed();
					}
					return lessonCommandService.complete(userId, lesson.getId()).courseCompleted();
				});
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
					assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
						+ "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class))
						.isPositive();
					assertThat(second.isDone()).isFalse();
				});
				testClock.advance(Duration.ofSeconds(10));
				return second;
			});
			var result = pending.get(10, TimeUnit.SECONDS);
			// then
			assertThat(result).isEqualTo(action.equals("READ"));
			assertThat(lessonRepository.findById(lesson.getId()).orElseThrow().getCompletedAt()).isEqualTo(firstTime);
			assertThat(courseRepository.findById(course.getId()).orElseThrow().getStatus())
				.isEqualTo(CourseStatus.COMPLETED);
			assertThat(courseRepository.findById(course.getId()).orElseThrow().getCompletedAt()).isEqualTo(firstTime);
			assertThat(cardRepository.findById(card.getId()).orElseThrow().getDueAt()).isEqualTo(firstTime);
			assertThat(cardRepository.findById(card.getId()).orElseThrow().getUpdatedAt()).isEqualTo(firstTime);
		}
	}

	@Test
	@DisplayName("완료 트랜잭션이 롤백되면 강의 카드 코스 로드맵이 모두 원래대로 남는다")
	void rollsBackAllCompletionWrites() {
		// given
		long userId = user();
		var roadmap = roadmapRepository
			.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId), RoadmapStatus.IN_PROGRESS));
		var course = courseRepository.save(CourseFixtures.withStatus(
			CourseFixtures.inRoadmap(roadmap, 1, "코스", "설명", List.of()), CourseStatus.LEARNING));
		var lesson = materialLesson(course, 1);
		cardRepository.save(CardFixtures.card(userId, lesson.getId(), 1, "질문", "답"));
		var before = state();
		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).execute(tx -> {
			lessonCommandService.complete(userId, lesson.getId());
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(state()).isEqualTo(before);
	}

	private long user() {
		return userRepository.save(UserFixtures.approved("me@example.com")).getId();
	}

	private Course course(long userId) {
		return courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(userId), CourseStatus.LEARNING));
	}

	private Lesson materialLesson(Course course, int position) {
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, position));
		materialRepository.save(MaterialFixtures.material(lesson, "본문"));
		return lesson;
	}

	private List<?> state() {
		return List
			.of("users", "courses", "lessons", "materials", "cards", "reviews", "roadmaps", "generation_jobs",
				"credit_entries")
			.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
