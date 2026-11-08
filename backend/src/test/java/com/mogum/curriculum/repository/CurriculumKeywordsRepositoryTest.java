package com.mogum.curriculum.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.curriculum.domain.Curriculum;
import com.mogum.curriculum.domain.CurriculumFixtures;
import com.mogum.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CurriculumKeywordsRepositoryTest {
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    private Curriculum curriculum;

    @BeforeEach
    void setUp() {
        var owner = users.save(UserFixtures.user("owner@example.com"));
        curriculum = curriculums.save(CurriculumFixtures.curriculum(owner.getId()));
    }

    @Test
    @DisplayName("text 배열은 키워드 순서와 중복을 보존한다")
    void preservesKeywordOrderAndDuplicates() {
        // given
        var saved = lessons.save(CurriculumFixtures.lesson(curriculum, 2, "키워드 강의", "목표", List.of("둘째", "첫째", "둘째")));

        // when
        var reloaded = lessons.findById(saved.getId()).orElseThrow();

        // then
        assertThat(reloaded.getKeywords()).containsExactly("둘째", "첫째", "둘째");
        assertThat(jdbc.queryForObject("SELECT pg_typeof(keywords)::text FROM lessons WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("text[]");
    }

    @ParameterizedTest(name = "키워드 [{0}]")
    @MethodSource("keywordLists")
    @DisplayName("키워드 목록은 비어 있거나 빈 원소를 가질 수 있다")
    void allowsEmptyAndBlankKeywords(List<String> keywords) {
        // given
        var saved = lessons.save(CurriculumFixtures.lesson(curriculum, 2, "키워드 강의", "목표", keywords));

        // when
        var reloaded = lessons.findById(saved.getId()).orElseThrow();

        // then
        assertThat(reloaded.getKeywords()).isEqualTo(keywords);
    }

    static Stream<List<String>> keywordLists() {
        return Stream.of(List.of(), List.of("", " "));
    }
}
