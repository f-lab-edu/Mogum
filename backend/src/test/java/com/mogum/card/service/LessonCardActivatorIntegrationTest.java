package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.repository.CardRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class LessonCardActivatorIntegrationTest {
	@Autowired
	LessonCardActivator lessonCardActivator;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("활성 카드의 빈 다음 볼 때만 채우며 기존 일정과 지운 코스 카드 및 FSRS 값은 보존한다")
	void activatesOnlyUnscheduledActiveCards() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		var course = courseRepository.save(CourseFixtures.single(userId));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var active = cardRepository.save(CardFixtures.card(userId, lesson.getId(), 1, "질문", "답"));
		var scheduled = cardRepository.save(CardFixtures.card(userId, lesson.getId(), 2, "질문", "답"));
		var deleted = cardRepository.save(CardFixtures.card(userId, lesson.getId(), 3, "질문", "답"));
		jdbcTemplate.update("UPDATE cards SET due_at = ? WHERE id = ?",
			Timestamp.from(testClock.instant().plusSeconds(86400)), scheduled.getId());
		jdbcTemplate.update("UPDATE cards SET status = 'COURSE_DELETED' WHERE id = ?", deleted.getId());
		var before = jdbcTemplate.queryForList("SELECT * FROM cards WHERE id IN (?, ?) ORDER BY id", scheduled.getId(),
			deleted.getId());
		testClock.advance(Duration.ofSeconds(10));
		// when
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(tx -> lessonCardActivator.activate(lesson.getId(), testClock.instant()));
		// then
		var saved = cardRepository.findById(active.getId()).orElseThrow();
		assertThat(saved.getDueAt()).isEqualTo(testClock.instant());
		assertThat(saved.getUpdatedAt()).isEqualTo(testClock.instant()).isAfter(active.getUpdatedAt());
		assertThat(saved).usingRecursiveComparison().ignoringFields("dueAt", "updatedAt").isEqualTo(active);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM cards WHERE id IN (?, ?) ORDER BY id", scheduled.getId(),
			deleted.getId())).isEqualTo(before);
	}

	@Test
	@DisplayName("카드 공개는 호출자의 완료 트랜잭션이 없으면 거절한다")
	void requiresCompletionTransaction() {
		// when & then
		assertThatThrownBy(() -> lessonCardActivator.activate(1L, testClock.instant()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}
}
