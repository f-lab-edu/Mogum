package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.Rating;
import com.mogum.card.repository.CardRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class CardCommandServiceConcurrencyIntegrationTest {

	@Autowired
	CardCommandService cardCommandService;
	@Autowired
	FsrsCalculator fsrsCalculator;
	@Autowired
	CardRepository cardRepository;
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
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "앞={0}, 뒤={1}, 뒤 거절={2}, 기록 수={3}")
	@CsvSource({"AGAIN,GOOD,,2", "EASY,GOOD,CARD_REVIEW_NOT_ALLOWED,1",
		"AGAIN,DELETE,,0", "DELETE,GOOD,NOT_FOUND,0", "DELETE,DELETE,NOT_FOUND,0"})
	@DisplayName("평가와 삭제는 같은 카드 행에서 기다리고 커밋된 상태를 다시 확인하여 고아 기록을 남기지 않는다")
	void serializesActionsOnSameCard(String first, String second, ErrorCode rejection, int reviews) throws Exception {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var card = CardFixtures.card(user.getId(), lesson.getId(), 1, "질문", "답");
		card.applyReview(new FsrsResult(card.getFsrsState(), 0, null, null, testClock.instant(), null));
		cardRepository.save(card);
		boolean deleted = first.equals("DELETE") || second.equals("DELETE");
		if (!deleted) {
			card.applyReview(fsrsCalculator.review(card, Rating.valueOf(first), testClock.instant()));
			if (rejection == null) {
				card.applyReview(fsrsCalculator.review(card, Rating.valueOf(second), testClock.instant()));
			}
		}
		try (var executor = Executors.newSingleThreadExecutor()) {
			// when — 앞 트랜잭션이 잠금을 쥔 동안 뒤 요청이 실제 DB에서 기다리는지 확인한다.
			var pending = new TransactionTemplate(platformTransactionManager).execute(tx -> {
				act(user.getId(), card.getId(), first);
				var future = executor.submit(() -> {
					try {
						act(user.getId(), card.getId(), second);
						return null;
					} catch (ApiException error) {
						return error.errorCode();
					}
				});
				await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
					assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
						+ "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class))
						.isPositive();
					assertThat(future.isDone()).isFalse();
				});
				return future;
			});
			var actualRejection = pending.get(10, TimeUnit.SECONDS);
			// then
			assertThat(actualRejection).isEqualTo(rejection);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isEqualTo(reviews);
			if (deleted) {
				assertThat(cardRepository.findById(card.getId())).isEmpty();
			} else {
				assertThat(cardRepository.findById(card.getId()).orElseThrow()).usingRecursiveComparison()
					.ignoringFields("updatedAt").isEqualTo(card);
			}
			assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM reviews r LEFT JOIN cards c ON c.id = r.card_id "
					+ "WHERE c.id IS NULL", Integer.class))
				.isZero();
		}
	}

	private void act(long userId, long cardId, String action) {
		if (action.equals("DELETE")) {
			cardCommandService.delete(userId, cardId);
		} else {
			cardCommandService.review(userId, cardId, Rating.valueOf(action));
		}
	}
}
