package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;

import jakarta.persistence.EntityManager;

@RepositoryTest
class StreakActivityRepositoryTest {
	@Autowired
	EntityManager entityManager;
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
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "DB 시간대={0}")
	@ValueSource(strings = {"UTC", "America/Los_Angeles"})
	@DisplayName("채운 날은 DB 시간대와 무관하게 서울 새벽 4시로 합쳐 중복 없이 최신순으로 읽는다")
	void readsDistinctSeoulDatesRegardlessOfDatabaseTimezone(String zone) {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1),
			Instant.parse("2026-11-24T19:00:00Z")));
		var card = cardRepository.save(CardFixtures.card(user.getId(), lesson.getId(), 1, "질문", "답"));
		testClock.set(Instant.parse("2026-11-24T18:59:00Z"));
		reviewRepository.save(ReviewFixtures.review(card, Rating.GOOD));
		testClock.set(Instant.parse("2026-11-24T19:00:00Z"));
		reviewRepository.save(ReviewFixtures.review(card, Rating.GOOD));
		testClock.set(Instant.parse("2026-11-25T19:00:00Z"));
		reviewRepository.save(ReviewFixtures.review(card, Rating.GOOD));
		var repository = new StreakActivityRepository(entityManager);
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		var today = LocalDate.of(2026, 11, 25);
		// when
		var dates = transactionTemplate.execute(tx -> {
			entityManager.createNativeQuery("SELECT set_config('TimeZone', :zone, true)", String.class)
				.setParameter("zone", zone).getSingleResult();
			return repository.findFilledDays(user.getId(), today);
		});
		// then
		assertThat(dates).containsExactly(today, today.minusDays(1));
	}
}
