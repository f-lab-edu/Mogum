package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardConceptFixtures;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.Concept;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Judgment;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class MasteryJudgmentServiceIntegrationTest {
	@Autowired
	MasteryJudgmentService masteryJudgmentService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	ConceptRepository conceptRepository;
	@Autowired
	CardConceptRepository cardConceptRepository;
	@Autowired
	ReviewRepository reviewRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("코스 지워짐 카드도 같은 무게로 세고 복습 횟수로 카드의 무게를 늘리지 않는다")
	void includesDeletedCourseCardsWithEqualWeightRegardlessOfReviewCount() {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var concept = concept(userId, "공통");
		var active = reviewedCard(lesson, 1, 10, testClock.instant());
		var deleted = reviewedCard(lesson, 2, 32, testClock.instant());
		link(active, concept);
		link(deleted, concept);
		reviewRepository.save(ReviewFixtures.review(active, Rating.GOOD));
		jdbcTemplate.update("UPDATE cards SET status = 'COURSE_DELETED', lesson_id = NULL WHERE id = ?",
			deleted.getId());

		// when
		var actual = masteryJudgmentService.judge(userId, List.of(concept.getId()), testClock.instant());

		// then
		assertThat(actual).containsExactlyEntriesOf(Map.of(concept.getId(), Judgment.WELL_KNOWN));
	}

	@ParameterizedTest(name = "학습 완료={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("복습 전 카드는 학습 완료 여부와 무관하게 세지 않는다")
	void excludesCardsWithoutReviews(boolean completed) {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var concept = concept(userId, "미복습");
		var card = CardFixtures.card(userId, lesson.getId(), 1, "질문", "답");
		card.applyReview(new FsrsResult(FsrsState.LEARNING, 0, null, null,
			completed ? testClock.instant() : null, null));
		link(cardRepository.save(card), concept);

		// when
		var actual = masteryJudgmentService.judge(userId, List.of(concept.getId()), testClock.instant());

		// then
		assertThat(actual).containsExactlyEntriesOf(Map.of(concept.getId(), Judgment.NO_RECORD));
	}

	@Test
	@DisplayName("마지막 평가 시각만 있고 실제 복습 기록 행이 없으면 기록 없음이다")
	void requiresAnActualReviewRow() {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var concept = concept(userId, "기록 기준");
		var card = CardFixtures.card(userId, lesson.getId(), 1, "질문", "답");
		card.applyReview(new FsrsResult(FsrsState.REVIEW, null, 21.0, 5.0,
			testClock.instant().plus(Duration.ofDays(21)), testClock.instant()));
		link(cardRepository.save(card), concept);

		// when
		var actual = masteryJudgmentService.judge(userId, List.of(concept.getId()), testClock.instant());

		// then
		assertThat(actual).containsExactlyEntriesOf(Map.of(concept.getId(), Judgment.NO_RECORD));
	}

	@Test
	@DisplayName("남의 카드와 남의 개념을 제외하고 없는 ID와 카드 없는 개념도 기록 없음으로 돌려준다")
	void isolatesBothCardAndConceptOwnersAndReturnsEveryRequestedId() {
		// given
		var mine = lesson("me@example.com");
		var other = lesson("other@example.com");
		long userId = mine.getCourse().getUserId();
		var ownConcept = concept(userId, "내 개념");
		var emptyConcept = concept(userId, "빈 개념");
		var foreignConcept = concept(other.getCourse().getUserId(), "남의 개념");
		var ownWeakCard = reviewedCard(mine, 1, 10, testClock.instant());
		var ownStrongCard = reviewedCard(mine, 2, 100, testClock.instant());
		var foreignCard = reviewedCard(other, 1, 100, testClock.instant());
		link(ownWeakCard, ownConcept);
		link(foreignCard, ownConcept);
		link(ownStrongCard, foreignConcept);
		link(foreignCard, foreignConcept);

		// when
		var actual = masteryJudgmentService.judge(userId,
			List.of(ownConcept.getId(), ownConcept.getId(), emptyConcept.getId(), foreignConcept.getId(),
				Long.MAX_VALUE),
			testClock.instant());

		// then
		assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
			ownConcept.getId(), Judgment.SHAKY, emptyConcept.getId(), Judgment.NO_RECORD,
			foreignConcept.getId(), Judgment.NO_RECORD, Long.MAX_VALUE, Judgment.NO_RECORD));
	}

	@Test
	@DisplayName("개념 둘이 붙은 카드의 상태를 두 개념 모두의 평균에 넣는다")
	void includesSharedCardInBothConcepts() {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var first = concept(userId, "첫 개념");
		var second = concept(userId, "둘째 개념");
		var card = reviewedCard(lesson, 1, 21, testClock.instant());
		link(card, first);
		link(card, second);

		// when
		var actual = masteryJudgmentService.judge(userId, List.of(first.getId(), second.getId()), testClock.instant());

		// then
		assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
			first.getId(), Judgment.WELL_KNOWN, second.getId(), Judgment.WELL_KNOWN));
	}

	@Test
	@DisplayName("같은 기록과 같은 시각이면 시계가 바뀌어도 같은 판정이며 카드와 복습 기록 및 수정 시각을 바꾸지 않는다")
	void repeatsJudgmentAtExplicitInstantWithoutWriting() {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var concept = concept(userId, "기억");
		link(reviewedCard(lesson, 1, 21, testClock.instant()), concept);
		var now = testClock.instant();
		var before = snapshot();
		var first = masteryJudgmentService.judge(userId, List.of(concept.getId()), now);
		testClock.advance(Duration.ofDays(365));

		// when
		var second = masteryJudgmentService.judge(userId, List.of(concept.getId()), now);

		// then
		assertThat(first).containsEntry(concept.getId(), Judgment.WELL_KNOWN);
		assertThat(second).isEqualTo(first);
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@DisplayName("새 복습 없이 시간이 흐르면 같은 기록도 흔들림으로 판정한다")
	void judgesAtTheSuppliedLaterInstant() {
		// given
		var lesson = lesson("me@example.com");
		long userId = lesson.getCourse().getUserId();
		var concept = concept(userId, "흐려짐");
		link(reviewedCard(lesson, 1, 21, testClock.instant()), concept);

		// when
		var actual = masteryJudgmentService.judge(userId, List.of(concept.getId()),
			testClock.instant().plus(Duration.ofDays(365)));

		// then
		assertThat(actual).containsEntry(concept.getId(), Judgment.SHAKY);
	}

	@Test
	@DisplayName("받은 개념 ID가 없으면 빈 판정을 돌려준다")
	void returnsEmptyMapForEmptyRequest() {
		// when
		var actual = masteryJudgmentService.judge(1L, List.of(), testClock.instant());

		// then
		assertThat(actual).isEmpty();
	}

	private Lesson lesson(String email) {
		long userId = userRepository.save(UserFixtures.approved(email)).getId();
		var course = courseRepository.save(CourseFixtures.single(userId));
		return lessonRepository.save(LessonFixtures.lesson(course, 1));
	}

	private Concept concept(long userId, String name) {
		return conceptRepository.save(ConceptFixtures.concept(userId, name, "정의", new float[] {1}));
	}

	private Card reviewedCard(Lesson lesson, int position, double stability, Instant reviewedAt) {
		var card = CardFixtures.card(lesson.getCourse().getUserId(), lesson.getId(), position, "질문", "답");
		card.applyReview(new FsrsResult(FsrsState.REVIEW, null, stability, 5.0,
			reviewedAt.plus(Duration.ofDays(21)), reviewedAt));
		var saved = cardRepository.save(card);
		reviewRepository.save(ReviewFixtures.review(saved, Rating.GOOD));
		return saved;
	}

	private void link(Card card, Concept concept) {
		cardConceptRepository.save(CardConceptFixtures.link(card, concept));
	}

	private List<List<Map<String, Object>>> snapshot() {
		return List.of(jdbcTemplate.queryForList("SELECT * FROM cards ORDER BY id"),
			jdbcTemplate.queryForList("SELECT * FROM reviews ORDER BY id"));
	}
}
