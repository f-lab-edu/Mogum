package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TodayReviewServiceIntegrationTest {
	@Autowired
	TodayReviewService todayReviewService;
	@Autowired
	FsrsCalculator fsrsCalculator;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	ReviewRepository reviewRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	TestClock testClock;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestRequests testRequests;

	@ParameterizedTest(name = "지금={0}, 하루 끝={1}")
	@CsvSource({"2026-11-24T03:00:00Z,2026-11-24T19:00:00Z",
		"2026-11-24T16:00:00Z,2026-11-24T19:00:00Z",
		"2026-11-24T19:00:00Z,2026-11-25T19:00:00Z"})
	@DisplayName("복습 중 카드는 밀린 것부터 다음 새벽 4시 직전까지이고 새벽 1시는 전날이다")
	void selectsReviewCardsUntilNextFourAm(Instant now, Instant nextDay) {
		// given
		testClock.set(now);
		var course = course();
		var overdue = card(course, FsrsState.REVIEW, now.minus(Duration.ofDays(3)));
		var lastMinute = card(course, FsrsState.REVIEW, nextDay.minusSeconds(60));
		card(course, FsrsState.REVIEW, nextDay);
		// when
		var result = todayReviewService.read(course.getUserId(), null);
		// then
		assertThat(result.cards()).extracting(value -> value.id()).containsExactly(overdue.getId(), lastMinute.getId());
		assertThat(result.remainingCount()).isEqualTo(2);
	}

	@ParameterizedTest(name = "상태={0}, 예정까지 초={1}, 다른 볼 카드={2}, 포함={3}")
	@CsvSource({"LEARNING,-1,false,true", "RELEARNING,-1,false,true",
		"LEARNING,0,false,true", "RELEARNING,0,false,true",
		"LEARNING,1,true,false", "RELEARNING,1,true,false",
		"LEARNING,600,false,true", "RELEARNING,600,false,true",
		"LEARNING,1199,false,true", "RELEARNING,1199,false,true",
		"LEARNING,1200,false,true", "RELEARNING,1200,false,true",
		"LEARNING,1201,false,false", "RELEARNING,1201,false,false",
		"LEARNING,600,true,false", "RELEARNING,600,true,false"})
	@DisplayName("학습 단계는 지금까지 고르고 다른 볼 카드가 없을 때만 20분까지 앞당겨 고른다")
	void selectsLearningCardsWithinScope(FsrsState state, long seconds, boolean otherDue, boolean included) {
		// given
		var course = course();
		var target = card(course, state, testClock.instant().plusSeconds(seconds));
		if (otherDue) {
			card(course, FsrsState.REVIEW, testClock.instant());
		}
		// when
		var result = todayReviewService.read(course.getUserId(), null);
		// then
		assertThat(result.cards().stream().anyMatch(value -> value.id() == target.getId())).isEqualTo(included);
		assertThat(result.remainingCount()).isEqualTo((otherDue ? 1 : 0) + (included ? 1 : 0));
	}

	@EnumSource(value = FsrsState.class, names = {"LEARNING", "RELEARNING"})
	@ParameterizedTest(name = "상태={0}")
	@DisplayName("지금 볼 학습 카드가 있으면 다른 학습 카드를 앞당겨 넣지 않는다")
	void suppressesAheadWhenLearningIsDue(FsrsState state) {
		// given
		var course = course();
		var due = card(course, state, testClock.instant());
		card(course, state, testClock.instant().plusSeconds(60));
		// when
		var result = todayReviewService.read(course.getUserId(), null);
		// then
		assertThat(result.cards()).extracting(value -> value.id()).containsExactly(due.getId());
	}

	@Test
	@DisplayName("학습 완료 전과 코스 지워짐 및 남의 카드는 제외하고 빈 목록을 돌려준다")
	void excludesHiddenAndForeignCards() {
		// given
		var course = course();
		var hidden = card(course, FsrsState.LEARNING, null);
		var deleted = card(course, FsrsState.LEARNING, testClock.instant());
		jdbcTemplate.update("UPDATE cards SET status = 'COURSE_DELETED', lesson_id = NULL WHERE id = ?",
			deleted.getId());
		var other = userRepository.save(UserFixtures.user("other@example.com"));
		var otherCourse = courseRepository.save(CourseFixtures.single(other.getId()));
		card(otherCourse, FsrsState.REVIEW, testClock.instant());
		// when
		var result = todayReviewService.read(course.getUserId(), null);
		// then
		assertThat(result.cards()).isEmpty();
		assertThat(result.remainingCount()).isZero();
		assertThat(cardRepository.findById(hidden.getId()).orElseThrow().getDueAt()).isNull();
	}

	@Test
	@DisplayName("코스로 거르면 다른 코스의 예정 카드를 빼고 거른 범위 안에서 앞당겨 본다")
	void collapsesOnlyInsideFilteredCourse() {
		// given
		var course = course();
		var otherCourse = courseRepository.save(CourseFixtures.single(course.getUserId()));
		var ahead = card(course, FsrsState.LEARNING, testClock.instant().plusSeconds(600));
		var due = card(otherCourse, FsrsState.REVIEW, testClock.instant());
		var all = todayReviewService.read(course.getUserId(), null);
		assertThat(all.cards()).extracting(value -> value.id()).containsExactly(due.getId());
		// when
		var result = todayReviewService.read(course.getUserId(), course.getId());
		// then
		assertThat(result.cards()).extracting(value -> value.id()).containsExactly(ahead.getId());
		assertThat(result.courseTitle()).isEqualTo(course.getTitle());
	}

	@ParameterizedTest(name = "코스에 카드 있음={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("내 코스에 카드가 없거나 오늘 예정이 없으면 제목과 빈 목록을 돌려준다")
	void returnsEmptyOwnedCourse(boolean hasCard) {
		// given
		var course = course();
		if (hasCard) {
			card(course, FsrsState.REVIEW, testClock.instant().plus(Duration.ofDays(3)));
		}
		// when
		var result = todayReviewService.read(course.getUserId(), course.getId());
		// then
		assertThat(result.cards()).isEmpty();
		assertThat(result.remainingCount()).isZero();
		assertThat(result.courseTitle()).isEqualTo(course.getTitle());
	}

	@ParameterizedTest(name = "남의 것={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("남의 코스와 없는 코스는 카드 유무와 상관없이 없는 것으로 답한다")
	void hidesForeignAndMissingCourses(boolean foreign) {
		// given
		var course = course();
		var other = userRepository.save(UserFixtures.user("other@example.com"));
		long courseId = foreign ? course.getId() : Long.MAX_VALUE;
		// when & then
		assertThatThrownBy(() -> todayReviewService.read(other.getId(), courseId))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("간격은 요청 시각의 미리 계산과 같고 예정 시각과 ID 순이며 읽기 전후 모든 저장값이 같다")
	void previewsInOrderWithoutMutation() {
		// given
		var course = course();
		var first = card(course, FsrsState.REVIEW, testClock.instant().minusSeconds(1));
		var second = card(course, FsrsState.RELEARNING, testClock.instant().minusSeconds(2));
		var third = card(course, FsrsState.LEARNING, first.getDueAt());
		reviewRepository.save(ReviewFixtures.review(first, Rating.GOOD));
		var before = snapshot();
		testClock.advance(Duration.ofSeconds(20));
		// when
		var result = todayReviewService.read(course.getUserId(), null);
		// then
		assertThat(result.cards()).extracting(value -> value.id()).containsExactly(second.getId(), first.getId(),
			third.getId());
		for (var item : result.cards()) {
			var saved = cardRepository.findById(item.id()).orElseThrow();
			var expected = fsrsCalculator.preview(saved, testClock.instant());
			assertThat(item.nextIntervalsSeconds()).hasSize(4);
			for (var rating : Rating.values()) {
				assertThat(item.nextIntervalsSeconds().get(rating)).isEqualTo(expected.get(rating).toSeconds());
			}
			assertThat(item.question()).isEqualTo(saved.getQuestion());
			assertThat(item.answer()).isEqualTo(saved.getAnswer());
		}
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "동작={0}")
	@ValueSource(strings = {"read", "start", "rename"})
	@DisplayName("코스 읽기와 시작 및 제목 고치기의 수는 같은 시각에 코스로 거른 카드 수와 같다")
	void fillsCourseResponseWithSameScopedCount(String action) {
		// given
		var course = course();
		card(course, FsrsState.LEARNING, testClock.instant().plusSeconds(600));
		var otherCourse = courseRepository.save(CourseFixtures.single(course.getUserId()));
		card(otherCourse, FsrsState.REVIEW, testClock.instant());
		var client = testRequests.asUser(course.getUserId());
		int expected = todayReviewService.read(course.getUserId(), course.getId()).cards().size();
		String uri = "/api/courses/" + course.getId();
		// when
		var result = switch (action) {
			case "start" -> client.post().uri(uri + "/start").exchange();
			case "rename" -> client.patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
				.body("{\"title\":\"새 제목\"}").exchange();
			default -> client.get().uri(uri).exchange();
		};
		// then
		result.expectStatus().isOk().expectBody().jsonPath("$.data.todayReviewCount").isEqualTo(expected);
		assertThat(expected).isEqualTo(1);
	}

	@Test
	@DisplayName("복습 HTTP 읽기는 질문과 답 및 간격을 반환하고 카드 원문을 로그에 남기지 않는다")
	void readsThroughHttpWithoutLoggingCardText(CapturedOutput output) {
		// given
		var course = course();
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		String marker = "private-review-card-marker";
		var card = CardFixtures.card(course.getUserId(), lesson.getId(), 1, marker, marker);
		card.applyReview(new FsrsResult(FsrsState.LEARNING, 0, null, null, testClock.instant(), null));
		cardRepository.save(card);
		var client = testRequests.asUser(course.getUserId());
		var before = snapshot();
		// when
		var result = client.get().uri("/api/reviews/today?courseId=" + course.getId()).exchange();
		// then
		result.expectStatus().isOk().expectBody()
			.jsonPath("$.data.cards[0].id").isEqualTo(card.getId())
			.jsonPath("$.data.cards[0].question").isEqualTo(marker)
			.jsonPath("$.data.cards[0].answer").isEqualTo(marker)
			.jsonPath("$.data.cards[0].nextIntervalsSeconds.AGAIN").isEqualTo(60)
			.jsonPath("$.data.remainingCount").isEqualTo(1)
			.jsonPath("$.data.courseTitle").isEqualTo(course.getTitle())
			.jsonPath("$.data.cards[0].dueAt").doesNotExist();
		assertThat(output.getAll()).doesNotContain(marker);
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@DisplayName("복습 코스 ID의 잘못된 입력은 응답과 로그에 원문을 남기지 않는다")
	void hidesMalformedQueryText(CapturedOutput output) {
		// given
		var course = course();
		var client = testRequests.asUser(course.getUserId());
		String marker = "private-review-query-marker";
		// when
		var result = client.get().uri("/api/reviews/today?courseId=" + marker).exchange();
		// then
		result.expectStatus().isBadRequest().expectBody()
			.jsonPath("$.code").isEqualTo("INVALID_REQUEST")
			.consumeWith(
				response -> assertThat(new String(response.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
					.doesNotContain(marker));
		assertThat(output.getAll()).doesNotContain(marker);
	}

	private Course course() {
		var user = userRepository
			.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"), UserStatus.APPROVED));
		return courseRepository.save(CourseFixtures.single(user.getId()));
	}

	private Card card(Course course, FsrsState state, Instant dueAt) {
		int position = jdbcTemplate.queryForObject("SELECT count(*) FROM lessons WHERE course_id = ?", Integer.class,
			course.getId()) + 1;
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, position));
		var card = CardFixtures.card(course.getUserId(), lesson.getId(), 1, "질문", "답");
		card.applyReview(new FsrsResult(state, state == FsrsState.REVIEW ? null : 0,
			5.0, 4.0, dueAt, testClock.instant().minus(Duration.ofDays(1))));
		return cardRepository.save(card);
	}

	private List<List<Map<String, Object>>> snapshot() {
		return List.of("cards", "reviews", "courses", "lessons").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
	}
}
