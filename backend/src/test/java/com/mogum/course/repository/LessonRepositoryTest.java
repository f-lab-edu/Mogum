package com.mogum.course.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.*;
import com.mogum.support.RepositoryTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class LessonRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("강의는 Jackson 3 JSON 매핑으로 개념의 순서와 한국어 및 계약의 JSON 키를 보존한다")
    void reloadsLessonWithOrderedKoreanConceptsUsingJackson3() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var concepts = List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("타입", "값의 종류"));
        var saved = lessons.save(LessonFixtures.lesson(course, 3, "변수와 타입", "값을 다루는 법을 배워요", concepts));

        // when
        var actual = lessons.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getCourse().getId()).isEqualTo(course.getId());
        assertThat(actual.getPosition()).isEqualTo(3);
        assertThat(actual.getTitle()).isEqualTo("변수와 타입");
        assertThat(actual.getDescription()).isEqualTo("값을 다루는 법을 배워요");
        assertThat(actual.getConcepts()).containsExactlyElementsOf(concepts);
        assertThat(actual.getCompletedAt()).isNull();
        assertThat(entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions()
                .getJsonFormatMapper()).isInstanceOf(Jackson3JsonFormatMapper.class);
        assertThat(jdbc.queryForObject("""
                SELECT concepts = '[{"name":"변수","definition":"값을 담는 이름"},
                  {"name":"타입","definition":"값의 종류"}]'::jsonb FROM lessons WHERE id = ?
                """, Boolean.class, actual.getId())).isTrue();
    }

    @Test
    @DisplayName("커밋 뒤 다시 읽은 강의의 코스는 트랜잭션 밖에서 초기화되지 않는다")
    void rejectsLazyCourseLoadingOutsideTransaction() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var saved = lessons.save(LessonFixtures.lesson(course, 1));
        var actual = lessons.findById(saved.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(actual.getCourse())).isFalse();

        // when & then
        assertThatThrownBy(() -> actual.getCourse().getTitle()).isInstanceOf(LazyInitializationException.class);
    }
}

