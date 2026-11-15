package com.mogum.card.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.*;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CardRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private CardRepository cards;
    @Autowired private TestClock clock;

    @Test
    @DisplayName("처음 만든 카드는 DB 상태 검사를 통과하고 빈 FSRS 값과 공용 시계의 시각을 보존한다")
    void reloadsNewCardWithInitialFsrsStateAndAuditing() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        var writtenAt = Instant.parse("2026-11-10T03:04:05Z");
        clock.set(writtenAt);
        var saved = cards.save(CardFixtures.card(user.getId(), lesson.getId(), 2, "변수란?", "값을 담는 이름"));

        // when
        var actual = cards.findById(saved.getId()).orElseThrow();

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
