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
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class ReviewRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private CardRepository cards;
    @Autowired private ReviewRepository reviews;
    @Autowired private TestClock clock;

    @ParameterizedTest(name = "평가 {0}")
    @EnumSource(Rating.class)
    @DisplayName("복습은 모든 평가와 공용 시계의 평가 시각을 저장하고 카드는 바꾸지 않는다")
    void reloadsRatingAndReviewTimeWithoutChangingCard(Rating rating) {
        // given
        var card = savedCard();
        var reviewedAt = Instant.parse("2026-11-10T03:04:05Z");
        clock.set(reviewedAt);
        var saved = reviews.save(ReviewFixtures.review(card, rating));

        // when
        var actual = reviews.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getCard().getId()).isEqualTo(card.getId());
        assertThat(actual.getRating()).isEqualTo(rating);
        assertThat(actual.getCreatedAt()).isEqualTo(reviewedAt);
        assertThat(actual.getUpdatedAt()).isEqualTo(reviewedAt);
        var unchanged = cards.findById(card.getId()).orElseThrow();
        assertThat(unchanged.getFsrsState()).isEqualTo(FsrsState.LEARNING);
        assertThat(unchanged.getFsrsStep()).isZero();
        assertThat(unchanged.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(unchanged.getDueAt()).isNull();
        assertThat(unchanged.getStability()).isNull();
        assertThat(unchanged.getDifficulty()).isNull();
        assertThat(unchanged.getLastReviewedAt()).isNull();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(card.getUpdatedAt());
    }

    @Test
    @DisplayName("커밋 뒤 다시 읽은 복습의 카드 내용은 트랜잭션 밖에서 지연 로딩할 수 없다")
    void rejectsLazyCardAccessOutsideTransaction() {
        // given
        var card = savedCard();
        var saved = reviews.save(ReviewFixtures.review(card, Rating.GOOD));
        var reloaded = reviews.findById(saved.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(reloaded.getCard())).isFalse();

        // when & then
        assertThatThrownBy(() -> reloaded.getCard().getQuestion()).isInstanceOf(LazyInitializationException.class);
    }

    private Card savedCard() {
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        return cards.save(CardFixtures.card(user.getId(), lesson.getId(), 2, "질문", "답"));
    }
}
