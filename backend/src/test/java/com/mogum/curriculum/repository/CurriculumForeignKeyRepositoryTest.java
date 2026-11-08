package com.mogum.curriculum.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CurriculumForeignKeyRepositoryTest {
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
        var owner = users.save(UserFixtures.user("owner@example.com"));
        group = groups.save(CurriculumFixtures.group(owner.getId()));
        step = steps.save(CurriculumFixtures.step(group));
        curriculum = curriculums.save(CurriculumFixtures.curriculumForStep(step));
        lesson = lessons.save(CurriculumFixtures.lesson(curriculum));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @CsvSource({"curriculum_groups,user_id,users", "curriculum_steps,group_id,curriculum_groups",
            "curriculums,user_id,users", "curriculums,step_id,curriculum_steps", "lessons,curriculum_id,curriculums"})
    @DisplayName("없는 부모는 FK로 거절한다")
    void rejectsMissingParent(String table, String column, String parent) {
        // when & then
        assertConstraint("23503", table + "_" + column + "_fkey", () -> update(table, column, -1L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + parent + " WHERE id = -1", Integer.class)).isEqualTo(0);
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @CsvSource({"curriculums,lessons_curriculum_id_fkey", "curriculum_groups,curriculum_steps_group_id_fkey",
            "curriculum_steps,curriculums_step_id_fkey"})
    @DisplayName("자식이 있는 부모는 삭제하지 못한다")
    void rejectsDeletingParentWithChildren(String table, String constraint) {
        // when & then
        assertConstraint("23503", constraint, () -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id(table)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id(table))).isEqualTo(1);
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @CsvSource({"curriculum_groups_user_id_fkey,a", "curriculum_steps_group_id_fkey,a",
            "curriculums_user_id_fkey,a", "curriculums_step_id_fkey,r", "lessons_curriculum_id_fkey,a"})
    @DisplayName("단계 연결만 RESTRICT이고 나머지 FK는 NO ACTION이다")
    void preservesForeignKeyDeleteActions(String constraint, String action) {
        // when
        var actual = jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = ?", String.class, constraint);

        // then
        assertThat(actual).isEqualTo(action);
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

    private void update(String table, String column, Object value) {
        // 테스트 SQL도 Auditing 밖의 수정 시각을 명시한다.
        if (column.equals("updated_at")) {
            jdbc.update("UPDATE " + table + " SET updated_at = ? WHERE id = ?", value, id(table));
            return;
        }
        jdbc.update("UPDATE " + table + " SET " + column + " = ?, updated_at = ? WHERE id = ?",
                value, Timestamp.from(clock.instant()), id(table));
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            if (constraint != null) assertThat(sql.getMessage()).contains(constraint);
        });
    }
}
