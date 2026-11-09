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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CardConceptRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private CardRepository cards;
    @Autowired private ConceptRepository concepts;
    @Autowired private CardConceptRepository links;
    @Autowired private TestClock clock;

    @Test
    @DisplayName("카드의 개념 연결은 record 복합 키와 지연 로딩 부모 및 공용 시계의 시각으로 다시 읽힌다")
    void reloadsLinkWithCompositeIdAndLazyParents() {
        // given
        var card = savedCard();
        var concept = concepts.save(ConceptFixtures.concept(card.getUserId(), "변수", "값을 담는 이름", new float[]{1, 2, 3}));
        var saved = links.save(CardConceptFixtures.link(card, concept));

        // when
        var actual = links.findById(new CardConceptId(card.getId(), concept.getId())).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isEqualTo(new CardConceptId(card.getId(), concept.getId()));
        assertThat(actual.getCard().getId()).isEqualTo(card.getId());
        assertThat(actual.getConcept().getId()).isEqualTo(concept.getId());
        assertThat(Hibernate.isInitialized(actual.getCard())).isFalse();
        assertThat(Hibernate.isInitialized(actual.getConcept())).isFalse();
        assertThat(actual.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(actual.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(actual.isNew()).isFalse();
    }

    @Test
    @DisplayName("레포지토리로 같은 카드와 개념의 새 연결 둘을 저장하면 기본 키 제약이 막는다")
    void rejectsDuplicateLinkOnSave() {
        // given
        var card = savedCard();
        var concept = concepts.save(ConceptFixtures.concept(card.getUserId(), "변수", "값을 담는 이름", new float[]{1, 2, 3}));
        var saved = links.save(CardConceptFixtures.link(card, concept));
        var duplicate = CardConceptFixtures.link(card, concept);

        // when & then
        assertThatThrownBy(() -> links.save(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getSQLState()).isEqualTo("23505");
                    assertThat(sql.getMessage()).contains("card_concepts_pkey");
                });
        assertThat(links.findById(saved.getId())).isPresent();
    }

    private Card savedCard() {
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        return cards.save(CardFixtures.card(user.getId(), lesson.getId(), 2, "질문", "답"));
    }
}
