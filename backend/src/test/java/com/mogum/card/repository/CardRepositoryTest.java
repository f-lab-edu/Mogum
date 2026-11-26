package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.CardStatus;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;

@RepositoryTest
class CardRepositoryTest {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private LessonRepository lessonRepository;
	@Autowired
	private CardRepository cardRepository;
	@Autowired
	private TestClock testClock;

	@Autowired
	private PlatformTransactionManager platformTransactionManager;

	@Test
	@DisplayName("강의 카드 벌크 숨기기는 대상 강의만 바꾸고 수정 시각을 직접 채운다")
	void hidesSelectedLessonCardsWithTimestamp() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var other = lessonRepository.save(LessonFixtures.lesson(course, 2));
		var target = cardRepository.save(CardFixtures.card(user.getId(), lesson.getId(), 1, "질문", "답"));
		var untouched = cardRepository.save(CardFixtures.card(user.getId(), other.getId(), 1, "다른 질문", "다른 답"));
		testClock.advance(Duration.ofSeconds(5));
		// when
		int count = new TransactionTemplate(platformTransactionManager)
			.execute(tx -> cardRepository.hideLessonCards(List.of(lesson.getId()), testClock.instant()));
		// then
		assertThat(count).isEqualTo(1);
		var saved = cardRepository.findById(target.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(CardStatus.COURSE_DELETED);
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(cardRepository.findById(untouched.getId()).orElseThrow()).usingRecursiveComparison()
			.isEqualTo(untouched);
	}

	@Test
	@DisplayName("처음 만든 카드는 DB 상태 검사를 통과하고 빈 FSRS 값과 공용 시계의 시각을 보존한다")
	void reloadsNewCardWithInitialFsrsStateAndAuditing() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));

		var writtenAt = Instant.parse("2026-11-10T03:04:05Z");
		testClock.set(writtenAt);
		var saved = cardRepository.save(CardFixtures.card(user.getId(), lesson.getId(), 2, "변수란?", "값을 담는 이름"));

		// when
		var actual = cardRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getId()).isPositive();

		assertThat(actual.getUserId()).isEqualTo(user.getId());
		assertThat(actual.getLessonId()).isEqualTo(lesson.getId());
		assertThat(actual.getPosition()).isEqualTo(2);
		assertThat(actual.getQuestion()).isEqualTo("변수란?");
		assertThat(actual.getAnswer()).isEqualTo("값을 담는 이름");

		assertThat(actual.getStatus()).isEqualTo(CardStatus.ACTIVE);
		assertThat(actual.getFsrsState()).isEqualTo(FsrsState.LEARNING);
		assertThat(actual.getFsrsStep()).isZero();
		assertThat(actual.getDueAt()).isNull();
		assertThat(actual.getStability()).isNull();
		assertThat(actual.getDifficulty()).isNull();
		assertThat(actual.getLastReviewedAt()).isNull();

		assertThat(actual.getCreatedAt()).isEqualTo(writtenAt);
		assertThat(actual.getUpdatedAt()).isEqualTo(writtenAt);
	}

	@ParameterizedTest(name = "FSRS 상태 {0}")
	@EnumSource(FsrsState.class)
	@DisplayName("평가 결과는 상태와 단계의 CHECK를 만족하고 여섯 칸이 저장과 재조회로 보존된다")
	void persistsReviewResultWithConsistentStateAndStep(FsrsState state) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var card = cardRepository.save(CardFixtures.card(user.getId(), lesson.getId(), 1, "질문", "답"));
		var createdAt = card.getCreatedAt();
		var reviewedAt = Instant.parse("2026-11-24T01:02:03Z");
		var dueAt = Instant.parse("2026-11-25T01:02:03Z");
		testClock.set(reviewedAt);
		Integer step = state == FsrsState.REVIEW ? null : 0;
		card.applyReview(new FsrsResult(state, step, 21.5, 4.2, dueAt, reviewedAt));
		cardRepository.save(card);

		// when
		var actual = cardRepository.findById(card.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(card);
		assertThat(actual.getFsrsState()).isEqualTo(state);
		assertThat(actual.getFsrsStep()).isEqualTo(step);
		assertThat(actual.getStability()).isEqualTo(21.5);
		assertThat(actual.getDifficulty()).isEqualTo(4.2);
		assertThat(actual.getDueAt()).isEqualTo(dueAt);
		assertThat(actual.getLastReviewedAt()).isEqualTo(reviewedAt);
		assertThat(actual.getCreatedAt()).isEqualTo(createdAt);
		assertThat(actual.getUpdatedAt()).isEqualTo(reviewedAt);
	}
}
