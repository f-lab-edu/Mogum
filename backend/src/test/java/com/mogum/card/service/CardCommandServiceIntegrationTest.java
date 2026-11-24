package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardConceptFixtures;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CardCommandServiceIntegrationTest {

	@Autowired
	CardCommandService cardCommandService;
	@Autowired
	TodayReviewService todayReviewService;
	@Autowired
	FsrsCalculator fsrsCalculator;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	ReviewRepository reviewRepository;
	@Autowired
	CardConceptRepository cardConceptRepository;
	@Autowired
	ConceptRepository conceptRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;
	@Autowired
	TestRequests testRequests;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "상태={0}, 평가={1}, 분={2}, 결과={3}, 단계={4}")
	@CsvSource({"LEARNING,AGAIN,1,LEARNING,0", "LEARNING,GOOD,10,LEARNING,1",
		"REVIEW,AGAIN,10,RELEARNING,0"})
	@DisplayName("학습 단계의 다시와 좋음 및 복습 중 다시는 라이브러리 기본 간격이고 바로 다시 읽힌다")
	void schedulesDefaultSameDaySteps(FsrsState state, Rating rating, long minutes, FsrsState next, int step) {
		// given
		var lesson = lesson();
		var card = card(lesson, 1, state, testClock.instant());
		// when
		cardCommandService.review(card.getUserId(), card.getId(), rating);
		// then
		var saved = cardRepository.findById(card.getId()).orElseThrow();
		assertThat(saved.getFsrsState()).isEqualTo(next);
		assertThat(saved.getFsrsStep()).isEqualTo(step);
		assertThat(saved.getDueAt()).isEqualTo(testClock.instant().plus(Duration.ofMinutes(minutes)));
		assertThat(todayReviewService.read(card.getUserId(), null).cards())
			.extracting(value -> value.id()).containsExactly(card.getId());
	}

	@ParameterizedTest(name = "바로 쉬움={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("쉬움 한 번 또는 좋음 두 번은 학습 단계를 마치고 날 단위로 넘어간다")
	void graduatesLearning(boolean easy) {
		// given
		var card = card(lesson(), 1, FsrsState.LEARNING, testClock.instant());
		if (!easy) {
			cardCommandService.review(card.getUserId(), card.getId(), Rating.GOOD);
			testClock.advance(Duration.ofMinutes(10));
		}
		// when
		cardCommandService.review(card.getUserId(), card.getId(), easy ? Rating.EASY : Rating.GOOD);
		// then
		var saved = cardRepository.findById(card.getId()).orElseThrow();
		assertThat(saved.getFsrsState()).isEqualTo(FsrsState.REVIEW);
		assertThat(saved.getFsrsStep()).isNull();
		assertThat(Duration.between(testClock.instant(), saved.getDueAt())).isGreaterThanOrEqualTo(Duration.ofDays(1));
	}

	@Test
	@DisplayName("처음 배우기의 다음 단계에서 다시를 누르면 첫 단계 1분 뒤로 돌아간다")
	void returnsToFirstLearningStep() {
		// given
		var card = card(lesson(), 1, FsrsState.LEARNING, testClock.instant());
		cardCommandService.review(card.getUserId(), card.getId(), Rating.GOOD);
		assertThat(cardRepository.findById(card.getId()).orElseThrow().getFsrsStep()).isEqualTo(1);
		// when
		cardCommandService.review(card.getUserId(), card.getId(), Rating.AGAIN);
		// then
		var saved = cardRepository.findById(card.getId()).orElseThrow();
		assertThat(saved.getFsrsState()).isEqualTo(FsrsState.LEARNING);
		assertThat(saved.getFsrsStep()).isZero();
		assertThat(saved.getDueAt()).isEqualTo(testClock.instant().plusSeconds(60));
	}

	@ParameterizedTest(name = "상태={0}, 평가={1}")
	@CsvSource({"LEARNING,AGAIN", "LEARNING,HARD", "LEARNING,GOOD", "LEARNING,EASY",
		"REVIEW,AGAIN", "REVIEW,HARD", "REVIEW,GOOD", "REVIEW,EASY",
		"RELEARNING,AGAIN", "RELEARNING,HARD", "RELEARNING,GOOD", "RELEARNING,EASY"})
	@DisplayName("평가는 FSRS 여섯 칸과 읽기의 간격을 그대로 적고 같은 시계의 기록과 수정 시각을 남긴다")
	void persistsCalculationAndAuditedReview(FsrsState state, Rating rating) {
		// given
		var card = card(lesson(), 1, state, testClock.instant());
		testClock.advance(Duration.ofSeconds(5));
		var now = testClock.instant();
		var expected = fsrsCalculator.review(card, rating, now);
		long preview = todayReviewService.read(card.getUserId(), null).cards().getFirst()
			.nextIntervalsSeconds().get(rating);
		// when
		cardCommandService.review(card.getUserId(), card.getId(), rating);
		// then
		var saved = cardRepository.findById(card.getId()).orElseThrow();
		assertThat(result(saved)).isEqualTo(expected);
		assertThat(Duration.between(now, saved.getDueAt()).toSeconds()).isEqualTo(preview);
		assertThat(saved.getUpdatedAt()).isEqualTo(now).isAfter(card.getUpdatedAt());
		assertThat(saved).usingRecursiveComparison().ignoringFields("fsrsState", "fsrsStep", "stability",
			"difficulty", "dueAt", "lastReviewedAt", "updatedAt").isEqualTo(card);
		var reviews = jdbcTemplate.queryForList("SELECT * FROM reviews WHERE card_id = ?", card.getId());
		assertThat(reviews).hasSize(1);
		assertThat(reviews.getFirst()).containsEntry("rating", rating.name());
		assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM reviews WHERE card_id = ?",
			java.sql.Timestamp.class, card.getId()).toInstant()).isEqualTo(now);
	}

	@ParameterizedTest(name = "지금={0}, 상태={1}, 예정={2}, 허용={3}")
	@CsvSource({"2026-11-24T16:00:00Z,REVIEW,2026-11-24T18:59:59Z,true",
		"2026-11-24T16:00:00Z,REVIEW,2026-11-24T19:00:00Z,false",
		"2026-11-24T16:00:00Z,REVIEW,2026-11-24T19:00:01Z,false",
		"2026-11-24T19:00:00Z,REVIEW,2026-11-25T18:59:59Z,true",
		"2026-11-24T19:00:00Z,REVIEW,2026-11-25T19:00:00Z,false",
		"2026-11-24T03:00:00Z,LEARNING,2026-11-24T03:20:00Z,true",
		"2026-11-24T03:00:00Z,LEARNING,2026-11-24T03:20:01Z,false",
		"2026-11-24T03:00:00Z,RELEARNING,2026-11-24T03:20:00Z,true",
		"2026-11-24T03:00:00Z,RELEARNING,2026-11-24T03:20:01Z,false"})
	@DisplayName("평가의 받기 조건은 새벽 4시와 학습 단계 20분 경계를 따르고 거절하면 저장값이 그대로다")
	void checksReviewWindow(Instant now, FsrsState state, Instant dueAt, boolean allowed) {
		// given
		testClock.set(now);
		var card = card(lesson(), 1, state, dueAt);
		var before = snapshot();
		// when & then
		if (allowed) {
			cardCommandService.review(card.getUserId(), card.getId(), Rating.AGAIN);
			assertThat(cardRepository.findById(card.getId()).orElseThrow().getLastReviewedAt()).isEqualTo(now);
		} else {
			assertThatThrownBy(() -> cardCommandService.review(card.getUserId(), card.getId(), Rating.AGAIN))
				.isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CARD_REVIEW_NOT_ALLOWED));
			assertThat(snapshot()).isEqualTo(before);
		}
	}

	@ParameterizedTest(name = "동작={0}, 경우={1}")
	@CsvSource({"review,FOREIGN", "review,MISSING", "review,HIDDEN", "review,COURSE_DELETED",
		"delete,FOREIGN", "delete,MISSING", "delete,HIDDEN", "delete,COURSE_DELETED"})
	@DisplayName("남의 것과 없는 것 및 보이지 않는 카드는 평가와 지우기 모두 없는 것으로 거절한다")
	void rejectsInvisibleCards(String action, String scenario) {
		// given
		var card = card(lesson(), 1, FsrsState.LEARNING, scenario.equals("HIDDEN") ? null : testClock.instant());
		long userId = scenario.equals("FOREIGN")
			? userRepository.save(UserFixtures.approved("other@example.com")).getId() : card.getUserId();
		long cardId = scenario.equals("MISSING") ? Long.MAX_VALUE : card.getId();
		if (scenario.equals("COURSE_DELETED")) {
			jdbcTemplate.update("UPDATE cards SET status = 'COURSE_DELETED', lesson_id = NULL WHERE id = ?",
				cardId);
		}
		var before = snapshot();
		// when & then
		assertThatThrownBy(() -> {
			if (action.equals("review")) {
				cardCommandService.review(userId, cardId, Rating.GOOD);
			} else {
				cardCommandService.delete(userId, cardId);
			}
		}).isInstanceOfSatisfying(ApiException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@DisplayName("지우기는 카드 기록과 개념 연결만 지우고 단독 개념과 같은 강의의 다른 카드는 남긴다")
	void deletesOnlyCardAndItsChildren() {
		// given
		var lesson = lesson();
		var target = card(lesson, 1, FsrsState.LEARNING, testClock.instant());
		var other = card(lesson, 2, FsrsState.LEARNING, testClock.instant());
		reviewRepository.save(ReviewFixtures.review(target, Rating.AGAIN));
		var otherReview = reviewRepository.save(ReviewFixtures.review(other, Rating.HARD));
		var sole = conceptRepository.save(ConceptFixtures.concept(target.getUserId(), "단독", "정의", new float[] {1}));
		var shared = conceptRepository.save(ConceptFixtures.concept(target.getUserId(), "공통", "정의", new float[] {1}));
		cardConceptRepository.save(CardConceptFixtures.link(target, sole));
		cardConceptRepository.save(CardConceptFixtures.link(target, shared));
		var otherLink = cardConceptRepository.save(CardConceptFixtures.link(other, shared));
		var client = testRequests.asUser(target.getUserId());
		// when
		var response = client.delete().uri("/api/cards/" + target.getId()).exchange();
		// then
		response.expectStatus().isNoContent().expectBody().isEmpty();
		assertThat(cardRepository.findById(target.getId())).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE card_id = ?", Integer.class,
			target.getId())).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM card_concepts WHERE card_id = ?", Integer.class,
			target.getId())).isZero();
		assertThat(conceptRepository.findById(sole.getId())).isPresent();
		assertThat(conceptRepository.findById(shared.getId())).isPresent();
		assertThat(cardConceptRepository.findById(otherLink.getId())).isPresent();
		assertThat(cardRepository.findById(other.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(other);
		assertThat(reviewRepository.findById(otherReview.getId())).isPresent();
		assertThat(todayReviewService.read(target.getUserId(), null).cards()).extracting(value -> value.id())
			.containsExactly(other.getId());
		client.get().uri("/api/courses/" + lesson.getCourse().getId()).exchange().expectStatus().isOk()
			.expectBody().jsonPath("$.data.todayReviewCount").isEqualTo(1);
		client.delete().uri("/api/cards/" + target.getId()).exchange().expectStatus().isNotFound();
		client.post().uri("/api/cards/" + target.getId() + "/reviews").contentType(MediaType.APPLICATION_JSON)
			.body("{\"rating\":\"GOOD\"}").exchange().expectStatus().isNotFound();
	}

	@Test
	@DisplayName("오늘 예정이 아닌 카드도 학습 완료 뒤면 지울 수 있다")
	void deletesFutureCard() {
		// given
		var card = card(lesson(), 1, FsrsState.REVIEW, testClock.instant().plus(Duration.ofDays(30)));
		// when
		cardCommandService.delete(card.getUserId(), card.getId());
		// then
		assertThat(cardRepository.findById(card.getId())).isEmpty();
	}

	@ParameterizedTest(name = "동작={0}")
	@ValueSource(strings = {"review", "delete"})
	@DisplayName("평가와 지우기의 트랜잭션이 롤백되면 카드와 자식 기록이 모두 복구된다")
	void rollsBackAllWrites(String action) {
		// given
		var card = card(lesson(), 1, FsrsState.LEARNING, testClock.instant());
		reviewRepository.save(ReviewFixtures.review(card, Rating.GOOD));
		var concept = conceptRepository.save(ConceptFixtures.concept(card.getUserId(), "개념", "정의", new float[] {1}));
		cardConceptRepository.save(CardConceptFixtures.link(card, concept));
		var before = snapshot();
		// when & then
		assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).execute(tx -> {
			if (action.equals("review")) {
				cardCommandService.review(card.getUserId(), card.getId(), Rating.AGAIN);
			} else {
				cardCommandService.delete(card.getUserId(), card.getId());
			}
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest(name = "평가={0}")
	@ValueSource(strings = {"GOOD", "private-rating-marker", "{broken"})
	@DisplayName("평가 HTTP는 원문과 카드 내용을 응답 오류 및 로그에 남기지 않는다")
	void handlesHttpWithoutLoggingInput(String rating, CapturedOutput output) {
		// given
		var card = card(lesson(), 1, FsrsState.LEARNING, testClock.instant());
		String marker = "private-rating-marker";
		var client = testRequests.asUser(card.getUserId());
		String body = rating.equals("{broken") ? "{\"" + marker : "{\"rating\":\"" + rating + "\"}";
		// when
		var response = client.post().uri("/api/cards/" + card.getId() + "/reviews")
			.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
		// then
		if (rating.equals("GOOD")) {
			response.expectStatus().isNoContent().expectBody().isEmpty();
		} else {
			response.expectStatus().isBadRequest().expectBody().jsonPath("$.code")
				.isEqualTo(rating.equals("{broken") ? "INVALID_REQUEST" : "FIELD_INVALID")
				.consumeWith(result -> assertThat(new String(result.getResponseBody(),
					java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(marker));
		}
		assertThat(output.getAll()).doesNotContain(marker, "private-card-question", "private-card-answer");
	}

	private Lesson lesson() {
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		return lessonRepository.save(LessonFixtures.lesson(course, 1));
	}

	private Card card(Lesson lesson, int position, FsrsState state, Instant dueAt) {
		var card = CardFixtures.card(lesson.getCourse().getUserId(), lesson.getId(), position,
			"private-card-question", "private-card-answer");
		card.applyReview(new FsrsResult(state, state == FsrsState.REVIEW ? null : 0,
			state == FsrsState.LEARNING ? null : 5.0, state == FsrsState.LEARNING ? null : 4.0, dueAt,
			state == FsrsState.LEARNING ? null : testClock.instant().minus(Duration.ofDays(1))));
		return cardRepository.save(card);
	}

	private static FsrsResult result(Card card) {
		return new FsrsResult(card.getFsrsState(), card.getFsrsStep(), card.getStability(), card.getDifficulty(),
			card.getDueAt(), card.getLastReviewedAt());
	}

	private List<?> snapshot() {
		return List.of("cards", "reviews", "card_concepts", "concepts").stream()
			.map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
	}
}
