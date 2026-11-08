package com.mogum.curriculum.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.persistence.AuditedEntity;
import com.mogum.curriculum.domain.Curriculum;
import com.mogum.curriculum.domain.CurriculumFixtures;
import com.mogum.curriculum.domain.CurriculumGroup;
import com.mogum.curriculum.domain.CurriculumStep;
import com.mogum.curriculum.domain.Lesson;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CurriculumAuditingRepositoryTest {
    @Autowired private CurriculumGroupRepository groups;
    @Autowired private CurriculumStepRepository steps;
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;
    private CurriculumGroup group;
    private CurriculumStep step;
    private Curriculum curriculum;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-10-28T01:02:03.123456Z"));
        var owner = users.save(UserFixtures.user("owner@example.com"));
        group = groups.save(CurriculumFixtures.group(owner.getId()));
        step = steps.save(CurriculumFixtures.step(group));
        curriculum = curriculums.save(CurriculumFixtures.curriculumForStep(step));
        lesson = lessons.save(CurriculumFixtures.lesson(curriculum));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @ValueSource(strings = {"curriculums", "curriculum_groups"})
    @DisplayName("주인 조회용 단일 컬럼 인덱스가 있다")
    void indexesOwnerId(String table) {
        // when
        var definitions = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", String.class, table);

        // then
        assertThat(definitions).anyMatch(definition -> definition.endsWith(" USING btree (user_id)"));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @ValueSource(strings = {"curriculum_groups", "curriculum_steps", "curriculums", "lessons"})
    @DisplayName("저장한 생성과 수정 시각은 고정 시계의 시각이다")
    void auditsSavedEntitiesWithTestClock(String table) {
        // when
        AuditedEntity reloaded = switch (table) {
            case "curriculum_groups" -> groups.findById(group.getId()).orElseThrow();
            case "curriculum_steps" -> steps.findById(step.getId()).orElseThrow();
            case "curriculums" -> curriculums.findById(curriculum.getId()).orElseThrow();
            case "lessons" -> lessons.findById(lesson.getId()).orElseThrow();
            default -> throw new IllegalArgumentException(table);
        };

        // then
        assertThat(reloaded.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(clock.instant());
        jdbc.queryForObject("SELECT created_at, updated_at FROM " + table + " WHERE id = ?", (row, index) -> {
            assertThat(row.getTimestamp("created_at").toInstant()).isEqualTo(clock.instant());
            assertThat(row.getTimestamp("updated_at").toInstant()).isEqualTo(clock.instant());
            return true;
        }, id(table));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                  AND column_name IN ('created_at', 'updated_at')
                  AND data_type = 'timestamp with time zone' AND column_default IS NULL
                """, Integer.class, table)).isEqualTo(2);
    }

    private long id(String table) {
        return switch (table) {
            case "curriculum_groups" -> group.getId();
            case "curriculum_steps" -> step.getId();
            case "curriculums" -> curriculum.getId();
            case "lessons" -> lesson.getId();
            default -> throw new IllegalArgumentException(table);
        };
    }
}
