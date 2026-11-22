package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class LessonCardWriterConcurrencyIntegrationTest {
	@Autowired
	private LessonCardWriter lessonCardWriter;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private LessonRepository lessonRepository;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@ParameterizedTest(name = "같은 사용자 {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("같은 사용자의 저장은 DB 잠금을 기다려 개념 하나만 만들고 다른 사용자의 저장은 기다리지 않는다")
	void serializesOnlySameUserUntilCommit(boolean sameUser) throws Exception {
		// given
		long firstUserId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long secondUserId = sameUser ? firstUserId
			: userRepository.save(UserFixtures.user("other@example.com")).getId();
		long firstLessonId = lesson(firstUserId);
		long secondLessonId = lesson(secondUserId);
		var firstSaved = new CountDownLatch(1);
		var releaseFirst = new CountDownLatch(1);
		var secondEntered = new CountDownLatch(1);
		var secondPid = new AtomicInteger();

		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> new TransactionTemplate(platformTransactionManager)
				.executeWithoutResult(status -> {
					lessonCardWriter.writeLessonCards(firstUserId, firstLessonId, cards("격리 수준"));
					firstSaved.countDown();
					awaitLatch(releaseFirst);
				}));
			try {
				assertThat(firstSaved.await(10, TimeUnit.SECONDS)).isTrue();
				// when — 앞 저장의 새 개념이 아직 커밋되지 않은 상태에서 둘째 트랜잭션을 겹친다.
				var second = executor.submit(() -> new TransactionTemplate(platformTransactionManager)
					.executeWithoutResult(status -> {
						secondPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
						secondEntered.countDown();
						lessonCardWriter.writeLessonCards(secondUserId, secondLessonId, cards("트랜잭션 격리 수준"));
					}));
				assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
				// then — 같은 사용자는 실제 DB 대기를 관찰해야 하므로 잠금이 없으면 이 단언이 실패한다.
				if (sameUser) {
					await().atMost(Duration.ofSeconds(5)).untilAsserted(
						() -> assertThat(jdbcTemplate.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0",
							Boolean.class, secondPid.get())).isTrue());
					assertThat(second.isDone()).isFalse();
				} else {
					second.get(5, TimeUnit.SECONDS);
					assertThat(first.isDone()).isFalse();
				}
				releaseFirst.countDown();
				first.get(10, TimeUnit.SECONDS);
				second.get(10, TimeUnit.SECONDS);
				assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM concepts", Integer.class))
					.isEqualTo(sameUser ? 1 : 2);
				assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cards", Integer.class)).isEqualTo(2);
				assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM card_concepts", Integer.class))
					.isEqualTo(2);
				assertThat(
					jdbcTemplate.queryForObject("SELECT count(DISTINCT concept_id) FROM card_concepts", Integer.class))
					.isEqualTo(sameUser ? 1 : 2);
			} finally {
				releaseFirst.countDown();
			}
		}
	}

	private long lesson(long userId) {
		var course = courseRepository.save(CourseFixtures.single(userId));
		return lessonRepository.save(LessonFixtures.lesson(course, 1)).getId();
	}

	private List<NewCard> cards(String name) {
		return List.of(new NewCard("질문", "답", List.of(new NewConcept(name, "정의", new float[] {1, 0}))));
	}

	private void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Test transaction was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Test transaction interrupted", exception);
		}
	}
}
