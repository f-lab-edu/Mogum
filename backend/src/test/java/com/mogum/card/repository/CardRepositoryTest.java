package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.CardStatus;
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
}
