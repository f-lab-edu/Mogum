package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.dto.StreakResponse;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
class StreakReadServiceIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

	@Autowired
	StreakReadService streakReadService;
	@Autowired
	TodayReviewService todayReviewService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	ReviewRepository reviewRepository;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	JdbcTemplate jdbcTemplate;

	@ParameterizedTest(name = "복습한 날(오늘에서 뺀 일수)={0}, 연속={1}, 오늘={2}")
	@CsvSource({"'',0,false", "0,1,true", "1 2,2,false", "2,0,false", "0 1 3,2,true",
		"0 1 2 3 4,5,true", "1 3,1,false"})
	@DisplayName("복습 스트릭은 오늘 또는 어제부터 채운 날이 이어지는 동안만 센다")
	void countsConsecutiveReviewDays(String offsets, int days, boolean filledToday) {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var card = card(user.getId());
		if (!offsets.isEmpty()) {
			Arrays.stream(offsets.split(" ")).mapToLong(Long::parseLong)
				.forEach(offset -> reviewAt(card, NOW.minus(Duration.ofDays(offset))));
		}
		testClock.set(NOW);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(days, filledToday));
	}

	@Test
	@DisplayName("오늘 강의 학습 완료만으로도 스트릭을 채우며 생성 시각을 세지 않는다")
	void fillsTodayFromLessonCompletionOnly() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		testClock.set(NOW.minus(Duration.ofDays(10)));
		completedLesson(user.getId(), NOW);
		testClock.set(NOW);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(1, true));
	}

	@Test
	@DisplayName("복습과 학습 완료를 합쳐 세고 같은 날 여러 번 해도 하루다")
	void mergesReviewsAndCompletionsWithoutDuplicatingDays() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var card = card(user.getId());
		reviewAt(card, NOW);
		reviewAt(card, NOW.plusSeconds(60));
		completedLesson(user.getId(), NOW);
		completedLesson(user.getId(), NOW.minus(Duration.ofDays(1)));
		reviewAt(card, NOW.minus(Duration.ofDays(2)));
		testClock.set(NOW);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(3, true));
	}

	@ParameterizedTest(name = "지금={0}, 활동={1}, 연속={2}, 오늘={3}, 강의={4}")
	@CsvSource({
		"2026-11-24T19:00:00Z,2026-11-24T18:59:00Z,1,false,false",
		"2026-11-24T19:00:00Z,2026-11-24T19:00:00Z,1,true,false",
		"2026-11-24T18:30:00Z,2026-11-24T16:00:00Z,1,true,false",
		"2026-11-24T19:00:00Z,2026-11-24T18:59:00Z,1,false,true",
		"2026-11-24T19:00:00Z,2026-11-24T19:00:00Z,1,true,true",
		"2026-11-24T18:30:00Z,2026-11-24T16:00:00Z,1,true,true"
	})
	@DisplayName("복습과 학습 완료 모두 서울 03시59분은 전날이고 지금이 03시30분이면 오늘도 전날이다")
	void usesSeoulFourAmBoundary(Instant now, Instant activityAt, int days, boolean filledToday, boolean lesson) {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		if (lesson) {
			completedLesson(user.getId(), activityAt);
		} else {
			reviewAt(card(user.getId()), activityAt);
		}
		testClock.set(now);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(days, filledToday));
	}

	@Test
	@DisplayName("남의 복습과 학습 완료 및 내 미완료 강의는 스트릭을 채우지 않는다")
	void excludesForeignActivityAndUncompletedLessons() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		card(user.getId());
		var other = userRepository.save(UserFixtures.approved("other@example.com"));
		reviewAt(card(other.getId()), NOW);
		completedLesson(other.getId(), NOW);
		testClock.set(NOW);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(0, false));
	}

	@Test
	@DisplayName("코스 지워짐 카드의 남아 있는 복습 기록도 스트릭을 채운다")
	void countsRetainedReviewsFromDeletedCourses() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var card = card(user.getId());
		reviewAt(card, NOW);
		jdbcTemplate.update("UPDATE cards SET status = 'COURSE_DELETED', lesson_id = NULL WHERE id = ?",
			card.getId());
		testClock.set(NOW);
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(1, true));
	}

	@Test
	@DisplayName("지운 복습과 학습 완료는 남지 않고 스트릭에서도 빠진다")
	void reflectsDeletedActivity() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var card = card(user.getId());
		reviewAt(card, NOW);
		completedLesson(user.getId(), NOW);
		testClock.set(NOW);
		assertThat(streakReadService.read(user.getId())).isEqualTo(new StreakResponse(1, true));
		jdbcTemplate.update("DELETE FROM reviews WHERE card_id = ?", card.getId());
		jdbcTemplate.update("DELETE FROM cards WHERE id = ?", card.getId());
		jdbcTemplate.update("DELETE FROM lessons WHERE course_id IN (SELECT id FROM courses WHERE user_id = ?)",
			user.getId());
		jdbcTemplate.update("DELETE FROM courses WHERE user_id = ?", user.getId());
		// when
		var result = streakReadService.read(user.getId());
		// then
		assertThat(result).isEqualTo(new StreakResponse(0, false));
	}

	@Test
	@DisplayName("스트릭 HTTP는 두 칸만 반환하고 카드와 복습 및 학습 완료 시각을 바꾸지 않는다")
	void readsThroughHttpWithoutChangingData() {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var card = card(user.getId());
		reviewAt(card, NOW);
		completedLesson(user.getId(), NOW.minus(Duration.ofDays(1)));
		testClock.set(NOW.plusSeconds(60));
		var client = testRequests.asUser(user.getId());
		var before = snapshot();
		// when
		var response = client.get().uri("/api/home/streak").exchange();
		// then
		response.expectStatus().isOk().expectBody()
			.json("{\"data\":{\"days\":2,\"filledToday\":true}}", JsonCompareMode.STRICT);
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "볼 카드 있음={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("홈 오늘 복습은 기존 전체 복습의 수와 같고 0도 싣고 아무것도 바꾸지 않는다")
	void readsSameUnfilteredReviewCount(boolean due) {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var first = card(user.getId());
		var second = card(user.getId());
		if (due) {
			schedule(first);
			schedule(second);
		}
		var other = userRepository.save(UserFixtures.approved("other@example.com"));
		var foreign = card(other.getId());
		schedule(foreign);
		int expected = todayReviewService.count(user.getId(), null);
		var client = testRequests.asUser(user.getId());
		var before = snapshot();
		// when
		var response = client.get().uri("/api/home/reviews").exchange();
		// then
		assertThat(expected).isEqualTo(due ? 2 : 0);
		response.expectStatus().isOk().expectBody()
			.json("{\"data\":{\"remainingCount\":%d}}".formatted(expected), JsonCompareMode.STRICT);
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "경로={0}")
	@ValueSource(strings = {"/api/home/reviews", "/api/home/streak"})
	@DisplayName("로그인하지 않은 홈 읽기는 공통 인증 오류로 거절한다")
	void rejectsAnonymousRequests(String path) {
		// when
		var response = testRequests.anonymous().get().uri(path).exchange();
		// then
		response.expectStatus().isUnauthorized().expectBody()
			.json("{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다\"}", JsonCompareMode.STRICT);
	}

	@ParameterizedTest(name = "경로={0}, 상태={1}")
	@CsvSource({"/api/home/reviews,APPLIED", "/api/home/reviews,REJECTED",
		"/api/home/streak,APPLIED", "/api/home/streak,REJECTED"})
	@DisplayName("승인되지 않은 사람의 홈 읽기는 기존 승인 필요 오류로 거절한다")
	void rejectsUnapprovedRequests(String path, UserStatus status) {
		// given
		var user = userRepository.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"), status));
		var client = testRequests.asUser(user.getId());
		// when
		var response = client.get().uri(path).exchange();
		// then
		response.expectStatus().isForbidden().expectBody().json(
			"{\"code\":\"ACCOUNT_NOT_APPROVED\",\"message\":\"승인된 계정이 필요합니다\"}", JsonCompareMode.STRICT);
	}

	private Card card(long userId) {
		var course = courseRepository.save(CourseFixtures.single(userId));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		return cardRepository.save(CardFixtures.card(userId, lesson.getId(), 1, "질문", "답"));
	}

	private void completedLesson(long userId, Instant completedAt) {
		var course = courseRepository.save(CourseFixtures.single(userId));
		lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1), completedAt));
	}

	private void reviewAt(Card card, Instant at) {
		testClock.set(at);
		reviewRepository.save(ReviewFixtures.review(card, Rating.GOOD));
	}

	private void schedule(Card card) {
		card.applyReview(new FsrsResult(FsrsState.LEARNING, 0, null, null, testClock.instant(), null));
		cardRepository.save(card);
	}

	private List<?> snapshot() {
		return List.of("users", "cards", "reviews", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
