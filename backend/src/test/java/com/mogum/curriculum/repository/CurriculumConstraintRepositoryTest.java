package com.mogum.curriculum.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.curriculum.domain.Curriculum;
import com.mogum.curriculum.domain.CurriculumFixtures;
import com.mogum.curriculum.domain.CurriculumGroup;
import com.mogum.curriculum.domain.CurriculumStep;
import com.mogum.curriculum.domain.CurriculumStatus;
import com.mogum.curriculum.domain.Lesson;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CurriculumConstraintRepositoryTest {
    @Autowired private CurriculumGroupRepository groups;
    @Autowired private CurriculumStepRepository steps;
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private User owner;
    private CurriculumGroup group;
    private CurriculumStep step;
    private Curriculum curriculum;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        owner = users.save(UserFixtures.user("owner@example.com"));
        group = groups.save(CurriculumFixtures.group(owner.getId()));
        step = steps.save(CurriculumFixtures.step(group, 1, "단계 제목", "단계 범위", "단계 주제", "단계 목적"));
        curriculum = curriculums.save(CurriculumFixtures.curriculumForStep(step));
        lesson = lessons.save(CurriculumFixtures.lesson(curriculum, 1, "강의 제목", "강의 목표", List.of("개념")));
    }

    @Test
    @DisplayName("같은 묶음의 같은 단계 순서는 거절한다")
    void rejectsDuplicateStepOrder() {
        // when & then
        assertConstraint("23505", "curriculum_steps_group_order_key", () ->
                steps.save(CurriculumFixtures.step(group, 1, "다른 제목", "다른 범위", "다른 주제", null)));
    }

    @Test
    @DisplayName("같은 단계에 커리큘럼 둘은 거절한다")
    void rejectsDuplicateStepCurriculum() {
        // when & then
        assertConstraint("23505", "curriculums_step_id_key", () -> curriculums.save(CurriculumFixtures.curriculumForStep(step)));
    }

    @Test
    @DisplayName("단계 없는 독립 커리큘럼은 여러 개 저장된다")
    void allowsMultipleSingleCurriculums() {
        // given
        var first = curriculums.save(CurriculumFixtures.curriculum(owner.getId(), "첫 주제", null, "첫 제목"));

        // when
        var second = curriculums.save(CurriculumFixtures.curriculum(owner.getId(), "둘째 주제", null, "둘째 제목"));

        // then
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(curriculums.findById(first.getId()).orElseThrow().getStep()).isNull();
        assertThat(curriculums.findById(second.getId()).orElseThrow().getStep()).isNull();
    }

    @Test
    @DisplayName("저장한 단계의 입력과 제목 묶음의 주인을 복사한다")
    void copiesPersistedStepAndGroupOwner() {
        // given
        var otherOwner = users.save(UserFixtures.user("other@example.com"));
        var otherGroup = groups.save(CurriculumFixtures.group(otherOwner.getId(), "다른 전체 주제", null, "다른 묶음 제목"));
        var otherStep = steps.save(CurriculumFixtures.step(otherGroup, 1, "다른 단계 제목", "범위", "다른 단계 주제", "다른 단계 목적"));

        // when
        Long id = transactions.execute(status -> {
            var persistedStep = steps.findById(otherStep.getId()).orElseThrow();
            assertThat(Hibernate.isInitialized(persistedStep.getGroup())).isFalse();
            return curriculums.save(CurriculumFixtures.curriculumForStep(persistedStep)).getId();
        });

        // then
        var reloaded = curriculums.findById(id).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(otherOwner.getId());
        assertThat(reloaded.getUserId()).isNotEqualTo(owner.getId());
        assertThat(reloaded.getTitle()).isEqualTo("다른 단계 제목");
        assertThat(reloaded.getTopic()).isEqualTo("다른 단계 주제");
        assertThat(reloaded.getPurpose()).isEqualTo("다른 단계 목적");
        assertThat(reloaded.getStatus()).isEqualTo(CurriculumStatus.DRAFT);
        assertThat(reloaded.getStep().getId()).isEqualTo(otherStep.getId());
        assertThat(jdbc.queryForObject("SELECT user_id FROM curriculums WHERE id = ?", Long.class, id)).isEqualTo(otherOwner.getId());
    }

    @ParameterizedTest(name = "테이블 {0}")
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    @DisplayName("주제는 한글 101자를 거절한다")
    void rejectsTopicAboveLengthLimit(String table) {
        // when & then
        assertConstraint("23514", table + "_topic_length", () -> update(table, "topic", "가".repeat(101)));
    }

    @ParameterizedTest(name = "테이블 {0}")
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    @DisplayName("목적은 한글 201자를 거절한다")
    void rejectsPurposeAboveLengthLimit(String table) {
        // when & then
        assertConstraint("23514", table + "_purpose_length", () -> update(table, "purpose", "나".repeat(201)));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @MethodSource("blankTextColumns")
    @DisplayName("주제 목적 제목류는 빈값과 공백만인 값을 거절한다")
    void rejectsBlankText(String table, String column, String blank) {
        // when & then
        assertConstraint("23514", table + "_" + column + "_not_blank", () -> update(table, column, blank));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @CsvSource({"curriculums,title", "curriculum_groups,title", "curriculum_steps,title",
            "curriculum_steps,scope", "lessons,title", "lessons,objective"})
    @DisplayName("제목류 길이 상한은 아직 두지 않는다")
    void allowsLongTitlesAndObjectives(String table, String column) {
        // when
        update(table, column, "긴".repeat(5000));

        // then
        assertThat(value(table, column)).isEqualTo("긴".repeat(5000));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @ValueSource(strings = {"curriculums", "lessons"})
    @DisplayName("목록에 없는 상태는 거절한다")
    void rejectsUnknownStatus(String table) {
        // when & then
        assertConstraint("23514", table + "_status_check", () -> update(table, "status", "GENERATING"));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @CsvSource({"curriculums,DRAFT", "curriculums,ACTIVE", "curriculums,COMPLETED",
            "lessons,NOT_STARTED", "lessons,READY", "lessons,COMPLETED"})
    @DisplayName("계약의 상태값을 모두 저장할 수 있다")
    void storesEverySupportedStatus(String table, String status) {
        // when
        update(table, "status", status);

        // then
        assertThat(value(table, "status")).isEqualTo(status);
    }

    @Test
    @DisplayName("같은 커리큘럼의 같은 강의 순서는 거절한다")
    void rejectsDuplicateLessonOrder() {
        // when & then
        assertConstraint("23505", "lessons_curriculum_order_key", () ->
                lessons.save(CurriculumFixtures.lesson(curriculum, 1, "다른 강의", "다른 목표", List.of())));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @ValueSource(strings = {"lessons", "curriculum_steps"})
    @DisplayName("순서 0은 거절한다")
    void rejectsZeroOrder(String table) {
        // when & then
        assertConstraint("23514", table + "_order_no_check", () -> update(table, "order_no", 0));
    }

    @ParameterizedTest(name = "{index}: {arguments}")
    @MethodSource("requiredColumns")
    @DisplayName("필수 컬럼은 NULL을 거절한다")
    void rejectsNullRequiredColumn(String table, String column) {
        // when & then
        assertConstraint("23502", null, () -> update(table, column, null));
    }

    @ParameterizedTest(name = "테이블 {0}")
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    @DisplayName("주제는 한글 100자를 저장한다")
    void storesTopicAtLengthLimit(String table) {
        // when
        update(table, "topic", "가".repeat(100));

        // then
        assertThat(value(table, "topic")).isEqualTo("가".repeat(100));
    }

    @ParameterizedTest(name = "테이블 {0}")
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    @DisplayName("목적은 한글 200자를 저장한다")
    void storesPurposeAtLengthLimit(String table) {
        // when
        update(table, "purpose", "나".repeat(200));

        // then
        assertThat(value(table, "purpose")).isEqualTo("나".repeat(200));
    }

    @ParameterizedTest(name = "테이블 {0}")
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    @DisplayName("목적은 NULL을 허용한다")
    void allowsNullPurpose(String table) {
        // when
        update(table, "purpose", null);

        // then
        assertThat(value(table, "purpose")).isNull();
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

    private String value(String table, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id(table));
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            if (constraint != null) assertThat(sql.getMessage()).contains(constraint);
        });
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> blankTextColumns() {
        return Stream.of("curriculums,topic", "curriculum_groups,topic", "curriculum_steps,topic",
                "curriculums,purpose", "curriculum_groups,purpose", "curriculum_steps,purpose",
                "curriculums,title", "curriculum_groups,title", "curriculum_steps,title",
                "curriculum_steps,scope", "lessons,title", "lessons,objective")
                .map(pair -> pair.split(","))
                .flatMap(pair -> Stream.of("", "   ", "\t\n")
                        .map(blank -> org.junit.jupiter.params.provider.Arguments.of(pair[0], pair[1], blank)));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> requiredColumns() {
        return Stream.of(
                new String[]{"curriculum_groups", "user_id", "topic", "title", "created_at", "updated_at"},
                new String[]{"curriculum_steps", "group_id", "order_no", "title", "scope", "topic", "created_at", "updated_at"},
                new String[]{"curriculums", "user_id", "topic", "title", "status", "created_at", "updated_at"},
                new String[]{"lessons", "curriculum_id", "order_no", "title", "objective", "keywords", "status", "created_at", "updated_at"}
        ).flatMap(columns -> Stream.of(columns).skip(1)
                .map(column -> org.junit.jupiter.params.provider.Arguments.of(columns[0], column)));
    }
}
