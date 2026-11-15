package com.mogum.course.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.*;
import com.mogum.support.RepositoryTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class MaterialRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private MaterialRepository materials;

    @Test
    @DisplayName("강의 자료는 강의 연결과 한국어 마크다운 본문을 저장한다")
    void reloadsMaterialWithLessonAndMarkdown() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        var saved = materials.save(MaterialFixtures.material(lesson, "# 변수\n값을 담는 이름이에요."));

        // when
        var actual = materials.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getLesson().getId()).isEqualTo(lesson.getId());
        assertThat(Hibernate.isInitialized(actual.getLesson())).isFalse();
        assertThat(actual.getBody()).isEqualTo("# 변수\n값을 담는 이름이에요.");
    }
}

